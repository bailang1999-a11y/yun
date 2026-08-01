#!/usr/bin/env node
/**
 * schema-check —— 数据库结构一致性校验
 *
 * 为什么重写
 *   旧实现只做静态文本解析：读 db/init/001_schema.sql，正则抠出表名列名，再扫
 *   mapper 里的注解 SQL 做比对。它**从不读 db/migrations/**，所以结构上不可能发现
 *   「001 被事后修改、但迁移链没跟上」这类问题 —— 而这正是导致老库缺
 *   cards.card_kind_id / payment_callback_logs、付款后发不出卡密的根因。
 *
 * 新架构：用真实 MySQL 做叠加验证，比对两条部署路径的最终结构
 *   路径 A（全新库） schema_check_fresh
 *       = db/init/001_schema.sql + db/migrations/* 全部
 *   路径 B（老库升级） schema_check_upgraded
 *       = 历史基线 001（从 git 取，见 LEGACY_REF）+ db/migrations/* 全部
 *
 *   历史基线是关键：如果两条路径都从当前 001 起跑，路径 B 必然是 A 的超集，
 *   diff 永远为空，脚本就又变成「静默通过」的橡皮图章。改用 001 被修改**之前**
 *   的版本作为老库起点，才能真正复现线上老库的处境。
 *
 *   随后从 information_schema 读两个库的完整结构（表/列/类型/可空/默认值/额外属性
 *   /索引名/索引列/唯一性），做双向 diff：
 *     - fresh 有、upgraded 没有  -> 迁移链缺失（老库升级后会缺结构）
 *     - upgraded 有、fresh 没有  -> 001 未同步（全新库会缺结构）
 *   任一方向有差异即 exit 1 并打印具体差异。
 *
 *   最后保留原有的 mapper SQL 比对能力，但改为基于 schema_check_upgraded 的**实际**
 *   结构校验（information_schema 读出来的真实列），比静态正则解析更准。
 *
 * 环境依赖
 *   docker + 正在运行的 xiyiyun-mysql 容器。任一不可用时**报错退出**，绝不静默通过
 *   —— 静默通过正是当前这个 bug 的成因。
 *
 * 用法
 *   node scripts/schema-check.mjs            # 或 npm run schema:check
 *   SCHEMA_CHECK_LEGACY_REF=<git-ref> ...    # 覆盖历史基线 ref
 *   SCHEMA_CHECK_KEEP=1 ...                  # 保留 scratch 库便于人工排查
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'

const CONTAINER = process.env.SCHEMA_CHECK_CONTAINER ?? 'xiyiyun-mysql'
const MYSQL_USER = process.env.SCHEMA_CHECK_MYSQL_USER ?? 'root'
const MYSQL_PASSWORD = process.env.SCHEMA_CHECK_MYSQL_PASSWORD ?? 'xiyiyun_root'
const FRESH_DB = 'schema_check_fresh'
const UPGRADED_DB = 'schema_check_upgraded'
/** 001 被加入 card_kind_id / payment_callback_logs 之前的最后一个提交。 */
const LEGACY_REF = process.env.SCHEMA_CHECK_LEGACY_REF ?? '37c7fa9'
const LEGACY_PATH = 'db/init/001_schema.sql'
const INIT_FILE = 'db/init/001_schema.sql'
const MIGRATIONS_DIR = 'db/migrations'
const MAPPER_DIR = 'backend/src/main/java/com/xiyiyun/shop/persistence/mapper'
/** 禁止误伤的真实库。 */
const PROTECTED_DBS = new Set(['xiyiyun', 'xiyiyun_test', 'mysql', 'information_schema', 'performance_schema', 'sys'])

const KEEP_SCRATCH = process.env.SCHEMA_CHECK_KEEP === '1'
let tempDir = null

function fail(message, hint) {
  console.error(`\n✖ schema-check 失败：${message}`)
  if (hint) {
    console.error(`\n${hint}`)
  }
  cleanup()
  process.exit(1)
}

// ---------------------------------------------------------------------------
// 底层执行：docker exec 进已运行的容器（不启动/不重启/不重建任何容器）
// ---------------------------------------------------------------------------

function run(cmd, args, options = {}) {
  return spawnSync(cmd, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options })
}

/** 跑一条 SQL 并返回 stdout（-N -B：无表头、tab 分隔）。 */
function mysqlQuery(sql, database) {
  const args = ['exec', '-i', CONTAINER, 'mysql', `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, '-N', '-B']
  if (database) args.push(database)
  const result = run('docker', args, { input: sql })
  if (result.status !== 0) {
    fail(
      `SQL 执行失败${database ? `（库 ${database}）` : ''}\n${sanitize(result.stderr)}`,
      `执行的 SQL：\n${sql.slice(0, 400)}`,
    )
  }
  return result.stdout
}

/**
 * 用 mysql 客户端直接执行整个 .sql 文件。
 * 迁移文件里有 DELIMITER 包裹的存储过程，必须交给客户端解析 —— 自己按分号切分会切坏。
 */
function mysqlRunFile(filePath, database) {
  const sql = fs.readFileSync(filePath, 'utf8')
  const args = ['exec', '-i', CONTAINER, 'mysql', `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, database]
  const result = run('docker', args, { input: sql })
  if (result.status !== 0) {
    fail(`执行 ${filePath} 到库 ${database} 失败\n${sanitize(result.stderr)}`)
  }
  return sanitize(result.stderr)
}

/** 抹掉 mysql 客户端那句无害的密码警告，避免噪音。 */
function sanitize(text) {
  return (text ?? '')
    .split('\n')
    .filter((line) => line.trim() && !line.includes('Using a password on the command line'))
    .join('\n')
}

// ---------------------------------------------------------------------------
// 环境预检 —— 任何一步不满足都必须响亮失败
// ---------------------------------------------------------------------------

function preflight() {
  if (!run('docker', ['--version']).status === 0) {
    fail('docker 不可用', '请确认已安装 docker 且当前用户有权限执行。')
  }
  const version = run('docker', ['--version'])
  if (version.status !== 0) {
    fail('docker 命令不可用', '请确认已安装 docker 且 docker daemon 正在运行。')
  }

  const ps = run('docker', ['ps', '--filter', `name=^/${CONTAINER}$`, '--format', '{{.Names}}'])
  if (ps.status !== 0) {
    fail('无法查询 docker 容器列表（daemon 可能未运行）', sanitize(ps.stderr))
  }
  if (ps.stdout.trim() !== CONTAINER) {
    fail(
      `MySQL 容器 ${CONTAINER} 未在运行`,
      [
        '本校验需要一个正在运行的 MySQL 容器来做结构叠加验证。',
        `请先启动它（例如：npm run dev:db），再重新执行本脚本。`,
        '注意：脚本本身不会启动/重启/重建任何容器。',
        '可用 SCHEMA_CHECK_CONTAINER 指定其它容器名。',
      ].join('\n'),
    )
  }

  const ping = run('docker', [
    'exec', '-i', CONTAINER, 'mysql', `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, '-N', '-B', '-e', 'SELECT 1',
  ])
  if (ping.status !== 0) {
    fail(
      `无法连接容器 ${CONTAINER} 内的 MySQL`,
      [sanitize(ping.stderr), '可用 SCHEMA_CHECK_MYSQL_USER / SCHEMA_CHECK_MYSQL_PASSWORD 覆盖凭据。'].join('\n'),
    )
  }

  for (const file of [INIT_FILE]) {
    if (!fs.existsSync(file)) fail(`找不到 ${file}`)
  }
  if (!fs.existsSync(MIGRATIONS_DIR)) fail(`找不到迁移目录 ${MIGRATIONS_DIR}`)
}

/** 取出历史基线 001（001 被事后修改之前的版本）。 */
function materializeLegacyBaseline() {
  const gitCheck = run('git', ['rev-parse', '--is-inside-work-tree'])
  if (gitCheck.status !== 0) {
    fail(
      '当前目录不是 git 仓库，无法取出历史基线 001',
      '老库升级路径需要「001 被修改之前」的版本作为起点。可用 SCHEMA_CHECK_LEGACY_REF 指定 ref。',
    )
  }
  const show = run('git', ['show', `${LEGACY_REF}:${LEGACY_PATH}`])
  if (show.status !== 0 || !show.stdout.trim()) {
    fail(
      `无法从 git ref ${LEGACY_REF} 取出 ${LEGACY_PATH}`,
      [sanitize(show.stderr), '可用 SCHEMA_CHECK_LEGACY_REF=<ref> 指定其它历史基线提交。'].join('\n'),
    )
  }
  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'schema-check-'))
  const legacyFile = path.join(tempDir, 'legacy_001_schema.sql')
  fs.writeFileSync(legacyFile, show.stdout)
  return legacyFile
}

function migrationFiles() {
  const files = fs
    .readdirSync(MIGRATIONS_DIR)
    .filter((name) => name.endsWith('.sql'))
    .sort() // 按文件名顺序 = 迁移顺序（002 -> 006 -> ...）
    .map((name) => path.join(MIGRATIONS_DIR, name))
  if (!files.length) fail(`${MIGRATIONS_DIR} 下没有任何 .sql 迁移文件`)
  return files
}

function recreateDatabase(name) {
  if (PROTECTED_DBS.has(name)) {
    fail(`拒绝操作受保护的数据库 ${name}`, '这是脚本内置的防误伤保护。')
  }
  mysqlQuery(
    `DROP DATABASE IF EXISTS \`${name}\`;
     CREATE DATABASE \`${name}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`,
  )
}

function dropDatabase(name) {
  if (PROTECTED_DBS.has(name)) return
  const args = [
    'exec', '-i', CONTAINER, 'mysql', `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`,
    '-e', `DROP DATABASE IF EXISTS \`${name}\`;`,
  ]
  run('docker', args)
}

function cleanup() {
  if (tempDir && fs.existsSync(tempDir)) {
    fs.rmSync(tempDir, { recursive: true, force: true })
    tempDir = null
  }
  if (KEEP_SCRATCH) {
    console.log(`\n(SCHEMA_CHECK_KEEP=1，保留 scratch 库 ${FRESH_DB} / ${UPGRADED_DB})`)
    return
  }
  dropDatabase(FRESH_DB)
  dropDatabase(UPGRADED_DB)
}

// ---------------------------------------------------------------------------
// 结构快照：从 information_schema 读真实结构
// ---------------------------------------------------------------------------

const SEP = '\u0001' // 列分隔符，避开数据里可能出现的 tab

/**
 * 读一个库的完整结构，返回可直接 diff 的 Map<签名, 描述>，外加供 mapper 校验用的
 * 表->列集合。
 */
function introspect(database) {
  const columnRows = mysqlQuery(
    `SELECT CONCAT_WS('${SEP}', TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
              IFNULL(COLUMN_DEFAULT, '<null>'), IFNULL(EXTRA, ''))
     FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = '${database}'
     ORDER BY TABLE_NAME, COLUMN_NAME;`,
    undefined,
  )

  const indexRows = mysqlQuery(
    `SELECT CONCAT_WS('${SEP}', TABLE_NAME, INDEX_NAME,
              GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','),
              IF(MAX(NON_UNIQUE) = 0, 'UNIQUE', 'NON_UNIQUE'))
     FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = '${database}'
     GROUP BY TABLE_NAME, INDEX_NAME
     ORDER BY TABLE_NAME, INDEX_NAME;`,
    undefined,
  )

  const tableRows = mysqlQuery(
    `SELECT CONCAT_WS('${SEP}', TABLE_NAME, ENGINE, TABLE_COLLATION)
     FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = '${database}' AND TABLE_TYPE = 'BASE TABLE'
     ORDER BY TABLE_NAME;`,
    undefined,
  )

  const structure = new Map()
  const tableColumnMap = new Map()

  for (const line of tableRows.split('\n')) {
    if (!line.trim()) continue
    const [table, engine, collation] = line.split(SEP)
    structure.set(`TABLE ${table}`, `引擎=${engine} 排序规则=${collation}`)
    if (!tableColumnMap.has(table)) tableColumnMap.set(table, new Set())
  }

  for (const line of columnRows.split('\n')) {
    if (!line.trim()) continue
    const [table, column, type, nullable, columnDefault, extra] = line.split(SEP)
    structure.set(
      `COLUMN ${table}.${column}`,
      `类型=${type} 可空=${nullable} 默认值=${columnDefault}${extra ? ` 额外=${extra}` : ''}`,
    )
    if (!tableColumnMap.has(table)) tableColumnMap.set(table, new Set())
    tableColumnMap.get(table).add(column)
  }

  for (const line of indexRows.split('\n')) {
    if (!line.trim()) continue
    const [table, index, columns, uniqueness] = line.split(SEP)
    structure.set(`INDEX ${table}.${index}`, `列=(${columns}) ${uniqueness}`)
  }

  return { structure, tableColumnMap }
}

// ---------------------------------------------------------------------------
// 双向 diff
// ---------------------------------------------------------------------------

function diffStructures(fresh, upgraded) {
  const missingInUpgraded = [] // fresh 有、upgraded 没有 -> 迁移链缺失
  const missingInFresh = [] // upgraded 有、fresh 没有 -> 001 未同步
  const mismatched = [] // 两边都有但定义不同

  for (const [key, freshValue] of fresh) {
    if (!upgraded.has(key)) {
      missingInUpgraded.push(`${key}  (${freshValue})`)
    } else if (upgraded.get(key) !== freshValue) {
      mismatched.push(`${key}\n      全新库: ${freshValue}\n      老库升级: ${upgraded.get(key)}`)
    }
  }
  for (const [key, upgradedValue] of upgraded) {
    if (!fresh.has(key)) {
      missingInFresh.push(`${key}  (${upgradedValue})`)
    }
  }

  return { missingInUpgraded, missingInFresh, mismatched }
}

function reportDiff({ missingInUpgraded, missingInFresh, mismatched }) {
  const blocks = []

  if (missingInUpgraded.length) {
    blocks.push(
      [
        `【迁移链缺失】${missingInUpgraded.length} 项：全新库有、老库升级后没有`,
        '  原因：有人改了 db/init/001_schema.sql，但没在 db/migrations/ 里补对应增量脚本。',
        '  后果：线上老库升级后会缺这些结构，功能在老库上直接失效。',
        '  修法：新增一个 db/migrations/NNN_*.sql，用幂等存储过程补齐下列结构。',
        ...missingInUpgraded.map((item) => `    - ${item}`),
      ].join('\n'),
    )
  }

  if (missingInFresh.length) {
    blocks.push(
      [
        `【001 未同步】${missingInFresh.length} 项：老库升级后有、全新库没有`,
        '  原因：迁移脚本建了新结构，但 db/init/001_schema.sql 没同步。',
        '  后果：全新部署的库会缺这些结构。',
        ...missingInFresh.map((item) => `    - ${item}`),
      ].join('\n'),
    )
  }

  if (mismatched.length) {
    blocks.push(
      [
        `【定义不一致】${mismatched.length} 项：两条路径都有该结构，但定义不同`,
        '  常见于类型/可空性/默认值漂移（如 INT vs INT UNSIGNED、NOT NULL vs NULL）。',
        ...mismatched.map((item) => `    - ${item}`),
      ].join('\n'),
    )
  }

  return blocks.join('\n\n')
}

// ---------------------------------------------------------------------------
// mapper SQL 校验（保留旧脚本的全部能力，数据源改为真实 information_schema）
// ---------------------------------------------------------------------------

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(dir, entry.name)
    return entry.isDirectory() ? walk(fullPath) : [fullPath]
  })
}

function splitTopLevel(value) {
  const items = []
  let current = ''
  let depth = 0
  let quote = null

  for (let i = 0; i < value.length; i += 1) {
    const char = value[i]
    const previous = value[i - 1]

    if (quote) {
      current += char
      if (char === quote && previous !== '\\') {
        quote = null
      }
      continue
    }

    if (char === "'" || char === '"' || char === '`') {
      quote = char
      current += char
      continue
    }

    if (char === '(') {
      depth += 1
    } else if (char === ')' && depth > 0) {
      depth -= 1
    }

    if (char === ',' && depth === 0) {
      if (current.trim()) {
        items.push(current.trim())
      }
      current = ''
      continue
    }

    current += char
  }

  if (current.trim()) {
    items.push(current.trim())
  }
  return items
}

function splitColumns(value) {
  return splitTopLevel(value)
    .map((item) => item.trim().replace(/`/g, ''))
    .filter(Boolean)
}

function stripSqlNoise(value) {
  return value
    .replace(/<script>|<\/script>/gi, ' ')
    .replace(/--.*$/gm, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function parseTableRefs(fromPart) {
  const aliases = new Map()
  const reserved = new Set(['ON', 'WHERE', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'JOIN', 'ORDER', 'GROUP', 'LIMIT', 'FOR'])

  // JSON_TABLE(...) 等表函数没有 information_schema 实体，不能按普通表校验。
  for (const match of fromPart.matchAll(/(?:FROM|JOIN)\s+`?((?:\w+\.)?\w+)`?\b(?!\s*\()(?:\s+(?:AS\s+)?`?(\w+)`?)?/gi)) {
    const [, rawTable, aliasCandidate] = match
    if (rawTable.toLowerCase().startsWith('information_schema.')) {
      continue
    }
    const table = rawTable.replace(/^.*\./, '')
    aliases.set(table, table)
    if (aliasCandidate && !reserved.has(aliasCandidate.toUpperCase())) {
      aliases.set(aliasCandidate, table)
    }
  }

  return aliases
}

/**
 * 校验 mapper 注解 SQL 引用的表/列是否真实存在。
 * tables 来自 schema_check_upgraded 的 information_schema，即「老库升级后的真实结构」
 * —— 用最保守的那条路径做基准，能同时发现「代码超前于迁移」的问题。
 */
function checkMapperSql(tables) {
  const errors = []

  function tableColumns(file, table) {
    const columns = tables.get(table)
    if (!columns) {
      errors.push(`${file}: 表 ${table} 在 ${UPGRADED_DB} 的实际结构中不存在`)
    }
    return columns
  }

  function validateColumn(file, table, column, context) {
    const columns = tableColumns(file, table)
    if (columns && !columns.has(column)) {
      errors.push(`${file}: ${context} 引用了不存在的列 ${table}.${column}`)
    }
  }

  function validateInsert(file, source) {
    for (const match of source.matchAll(/INSERT\s+(?:IGNORE\s+)?INTO\s+`?(\w+)`?\s*\(([\s\S]*?)\)\s*(?:VALUES|SELECT)/gi)) {
      const [, table, rawColumns] = match
      const columns = tableColumns(file, table)
      if (!columns) continue
      for (const column of splitColumns(rawColumns)) {
        if (!columns.has(column)) {
          errors.push(`${file}: INSERT 引用了不存在的列 ${table}.${column}`)
        }
      }
    }
  }

  function validateUpdate(file, source) {
    for (const match of source.matchAll(/(?:^|[^\w])UPDATE\s+`?(\w+)`?\s+SET\s+([\s\S]*?)(?:\s+WHERE\b|"""\)|"\)|$)/gi)) {
      const [, table, assignments] = match
      const columns = tableColumns(file, table)
      if (!columns) continue
      for (const assignment of splitTopLevel(assignments)) {
        const column = assignment.trim().match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?\s*=/)?.[1]
        if (column && !columns.has(column)) {
          errors.push(`${file}: UPDATE 引用了不存在的列 ${table}.${column}`)
        }
      }
    }
  }

  function validateUpsertUpdate(file, source) {
    for (const match of source.matchAll(/INSERT\s+(?:IGNORE\s+)?INTO\s+`?(\w+)`?[\s\S]*?ON\s+DUPLICATE\s+KEY\s+UPDATE\s+([\s\S]*?)(?:"""\)|"\)|$)/gi)) {
      const [, table, assignments] = match
      const columns = tableColumns(file, table)
      if (!columns) continue
      for (const assignment of splitTopLevel(assignments)) {
        const column = assignment.trim().match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?\s*=/)?.[1]
        if (column && !columns.has(column)) {
          errors.push(`${file}: ON DUPLICATE KEY UPDATE 引用了不存在的列 ${table}.${column}`)
        }
      }
    }
  }

  function validateDelete(file, source) {
    for (const match of source.matchAll(/DELETE\s+FROM\s+`?(\w+)`?/gi)) {
      tableColumns(file, match[1])
    }
  }

  function validateAlter(file, source) {
    for (const match of source.matchAll(/ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?/gi)) {
      const [, table, column] = match
      validateColumn(file, table, column, 'ALTER TABLE ADD COLUMN')
    }
  }

  function validateSelectList(file, source) {
    for (const match of source.matchAll(/SELECT\s+([\s\S]*?)\s+FROM\s+([\s\S]*?)(?:\s+WHERE\b|\s+ORDER\s+BY\b|\s+GROUP\s+BY\b|\s+LIMIT\b|\s+FOR\s+UPDATE\b|"""\)|"\)|$)/gi)) {
      const [, selectPart, fromPart] = match
      const aliases = parseTableRefs(`FROM ${fromPart}`)
      const uniqueTables = new Set(aliases.values())

      for (const table of uniqueTables) {
        tableColumns(file, table)
      }

      for (const item of splitTopLevel(selectPart)) {
        const expression = stripSqlNoise(item)
        if (!expression || expression === '*' || /\bCOUNT\s*\(\s*\*\s*\)/i.test(expression)) {
          continue
        }

        const qualified = expression.match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?\.`?([a-zA-Z][a-zA-Z0-9_]*)`?(?:\s+AS\s+\w+)?$/i)
        if (qualified) {
          const [, alias, column] = qualified
          const table = aliases.get(alias)
          if (!table) {
            errors.push(`${file}: SELECT 引用了未知表别名 ${alias}`)
            continue
          }
          validateColumn(file, table, column, 'SELECT')
          continue
        }

        const unqualified = expression.match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?(?:\s+AS\s+\w+)?$/i)
        if (unqualified && uniqueTables.size === 1) {
          validateColumn(file, [...uniqueTables][0], unqualified[1], 'SELECT')
        }
      }
    }
  }

  if (!fs.existsSync(MAPPER_DIR)) {
    return { errors: [`找不到 mapper 目录 ${MAPPER_DIR}`], fileCount: 0 }
  }

  const mapperFiles = walk(MAPPER_DIR).filter((file) => file.endsWith('.java'))
  for (const file of mapperFiles) {
    const source = fs.readFileSync(file, 'utf8')
    validateInsert(file, source)
    validateUpdate(file, source)
    validateUpsertUpdate(file, source)
    validateDelete(file, source)
    validateAlter(file, source)
    validateSelectList(file, source)
  }

  return { errors: [...new Set(errors)], fileCount: mapperFiles.length }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

/**
 * 集成测试基座在 backend/src/test/resources/db/ 下保存了一份建库脚本副本
 * （容器内只挂载了 ./backend，看不到仓库根的 db/）。副本一旦与源文件漂移，
 * 集成测试跑的就不是生产结构，而这种漂移不会有任何报错。这里做源文件侧的校验。
 */
function checkTestResourceCopies() {
  const manifest = 'backend/src/test/resources/db/CHECKSUMS.txt'
  if (!fs.existsSync(manifest)) {
    console.log('  (跳过：未发现集成测试 SQL 副本清单)')
    return
  }
  const drift = []
  for (const line of fs.readFileSync(manifest, 'utf8').split('\n')) {
    const matched = line.trim().match(/^([0-9a-f]{64})\s+(.+)$/)
    if (!matched) continue
    const [, expected, source] = matched
    if (!fs.existsSync(source)) {
      drift.push(`${source} 已不存在，但仍列在清单中`)
      continue
    }
    const actual = createHash('sha256').update(fs.readFileSync(source)).digest('hex')
    if (actual !== expected) {
      drift.push(`${source} 已变更，但 backend/src/test/resources/db/ 下的副本未同步`)
    }
  }
  if (drift.length) {
    console.error(`\n【集成测试 SQL 副本漂移】${drift.length} 项：`)
    console.error(drift.map((item) => `    - ${item}`).join('\n'))
    console.error('\n  修复：')
    console.error('    cp db/init/001_schema.sql backend/src/test/resources/db/init/')
    console.error('    cp db/migrations/*.sql    backend/src/test/resources/db/migrations/')
    console.error('    shasum -a 256 db/init/001_schema.sql db/migrations/*.sql \\')
    console.error('      > backend/src/test/resources/db/CHECKSUMS.txt')
    fail(`集成测试用的建库脚本副本与 db/ 下源文件不一致，共 ${drift.length} 项`)
  }
  console.log('  ✓ 集成测试 SQL 副本与 db/ 下源文件一致')
}

function main() {
  preflight()

  console.log('[副本] 校验集成测试基座的建库脚本副本未漂移')
  checkTestResourceCopies()
  console.log('')

  const migrations = migrationFiles()
  const legacyBaseline = materializeLegacyBaseline()

  console.log('schema-check：用真实 MySQL 叠加验证两条部署路径的最终结构')
  console.log(`  容器          ${CONTAINER}`)
  console.log(`  历史基线      git ${LEGACY_REF}:${LEGACY_PATH}`)
  console.log(`  迁移文件      ${migrations.map((file) => path.basename(file)).join(' -> ')}`)
  console.log('')

  // 路径 A：全新库 = 当前 001 + 全部迁移
  console.log(`[路径 A] 全新库 ${FRESH_DB}`)
  recreateDatabase(FRESH_DB)
  console.log(`  执行 ${INIT_FILE}`)
  mysqlRunFile(INIT_FILE, FRESH_DB)
  for (const file of migrations) {
    console.log(`  执行 ${file}`)
    mysqlRunFile(file, FRESH_DB)
  }

  // 路径 B：老库升级 = 历史基线 001 + 全部迁移
  console.log(`\n[路径 B] 老库升级 ${UPGRADED_DB}`)
  recreateDatabase(UPGRADED_DB)
  console.log(`  执行 历史基线 001 (git ${LEGACY_REF})`)
  mysqlRunFile(legacyBaseline, UPGRADED_DB)
  for (const file of migrations) {
    console.log(`  执行 ${file}`)
    mysqlRunFile(file, UPGRADED_DB)
  }

  // 双向 diff
  console.log('\n[对比] 从 information_schema 读取两库完整结构并做双向 diff')
  const fresh = introspect(FRESH_DB)
  const upgraded = introspect(UPGRADED_DB)
  console.log(`  ${FRESH_DB}    ${fresh.tableColumnMap.size} 张表 / ${fresh.structure.size} 项结构`)
  console.log(`  ${UPGRADED_DB} ${upgraded.tableColumnMap.size} 张表 / ${upgraded.structure.size} 项结构`)

  const diff = diffStructures(fresh.structure, upgraded.structure)
  const diffCount = diff.missingInUpgraded.length + diff.missingInFresh.length + diff.mismatched.length

  if (diffCount > 0) {
    console.error(`\n${reportDiff(diff)}`)
    fail(
      `两条部署路径结构不一致，共 ${diffCount} 项差异`,
      [
        '全新库（001 + 迁移）与老库升级（历史基线 001 + 迁移）必须产出完全相同的结构。',
        '出现差异说明 db/init/001_schema.sql 与 db/migrations/ 已经脱节 ——',
        '这类问题不会让后端启动失败，但会在真实交易链路上炸（例如付款后发不出卡密）。',
      ].join('\n'),
    )
  }
  console.log('  ✓ 双向 diff 无差异，两条路径结构完全一致')

  // mapper SQL 校验
  console.log(`\n[mapper] 基于 ${UPGRADED_DB} 的实际结构校验 mapper 注解 SQL`)
  const mapper = checkMapperSql(upgraded.tableColumnMap)
  if (mapper.errors.length) {
    console.error(`\n【mapper SQL 不匹配】${mapper.errors.length} 项：`)
    console.error(mapper.errors.map((item) => `    - ${item}`).join('\n'))
    fail(`mapper 注解 SQL 与数据库实际结构不匹配，共 ${mapper.errors.length} 项`)
  }
  console.log(`  ✓ ${mapper.fileCount} 个 mapper 文件的表名列名全部匹配实际结构`)

  cleanup()
  console.log('\n✔ schema-check 通过：全新库与老库升级路径结构一致，mapper SQL 与实际结构匹配')
}

process.on('uncaughtException', (error) => {
  console.error(`\n✖ schema-check 异常终止：${error?.stack ?? error}`)
  cleanup()
  process.exit(1)
})

main()
