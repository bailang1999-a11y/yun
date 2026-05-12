import fs from 'node:fs'
import path from 'node:path'

const schema = fs.readFileSync('db/init/001_schema.sql', 'utf8')

const tables = new Map()
for (const match of schema.matchAll(/CREATE TABLE IF NOT EXISTS\s+`?(\w+)`?\s*\(([\s\S]*?)\) ENGINE=/g)) {
  const [, table, body] = match
  const columns = new Set()
  for (const line of body.split('\n')) {
    const column = line.match(
      /^\s*`?([a-zA-Z][a-zA-Z0-9_]*)`?\s+(BIGINT|VARCHAR|TEXT|MEDIUMTEXT|DATETIME|DECIMAL|INT|TINYINT|JSON|VARBINARY|CHAR|GENERATED)/i,
    )
    if (column) {
      columns.add(column[1])
    }
  }
  tables.set(table, columns)
}

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

function tableColumns(file, table, errors) {
  const columns = tables.get(table)
  if (!columns) {
    errors.push(`${file}: table ${table} is not defined in db/init/001_schema.sql`)
  }
  return columns
}

function validateColumn(file, table, column, context, errors) {
  const columns = tableColumns(file, table, errors)
  if (columns && !columns.has(column)) {
    errors.push(`${file}: ${context} references missing column ${table}.${column}`)
  }
}

function stripSqlNoise(value) {
  return value
    .replace(/<script>|<\/script>/gi, ' ')
    .replace(/--.*$/gm, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function validateInsert(file, source, errors) {
  for (const match of source.matchAll(/INSERT\s+(?:IGNORE\s+)?INTO\s+`?(\w+)`?\s*\(([\s\S]*?)\)\s*(?:VALUES|SELECT)/gi)) {
    const [, table, rawColumns] = match
    const columns = tableColumns(file, table, errors)
    if (!columns) {
      continue
    }
    for (const column of splitColumns(rawColumns)) {
      if (!columns.has(column)) {
        errors.push(`${file}: INSERT references missing column ${table}.${column}`)
      }
    }
  }
}

function validateUpdate(file, source, errors) {
  for (const match of source.matchAll(/(?:^|[^\w])UPDATE\s+`?(\w+)`?\s+SET\s+([\s\S]*?)(?:\s+WHERE\b|"""\)|"\)|$)/gi)) {
    const [, table, assignments] = match
    const columns = tableColumns(file, table, errors)
    if (!columns) {
      continue
    }
    for (const assignment of splitTopLevel(assignments)) {
      const column = assignment.trim().match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?\s*=/)?.[1]
      if (column && !columns.has(column)) {
        errors.push(`${file}: UPDATE references missing column ${table}.${column}`)
      }
    }
  }
}

function validateUpsertUpdate(file, source, errors) {
  for (const match of source.matchAll(/INSERT\s+(?:IGNORE\s+)?INTO\s+`?(\w+)`?[\s\S]*?ON\s+DUPLICATE\s+KEY\s+UPDATE\s+([\s\S]*?)(?:"""\)|"\)|$)/gi)) {
    const [, table, assignments] = match
    const columns = tableColumns(file, table, errors)
    if (!columns) {
      continue
    }
    for (const assignment of splitTopLevel(assignments)) {
      const column = assignment.trim().match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?\s*=/)?.[1]
      if (column && !columns.has(column)) {
        errors.push(`${file}: ON DUPLICATE KEY UPDATE references missing column ${table}.${column}`)
      }
    }
  }
}

function validateDelete(file, source, errors) {
  for (const match of source.matchAll(/DELETE\s+FROM\s+`?(\w+)`?/gi)) {
    tableColumns(file, match[1], errors)
  }
}

function validateAlter(file, source, errors) {
  for (const match of source.matchAll(/ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?/gi)) {
    const [, table, column] = match
    validateColumn(file, table, column, 'ALTER TABLE ADD COLUMN', errors)
  }
}

function parseTableRefs(fromPart) {
  const aliases = new Map()
  const reserved = new Set(['ON', 'WHERE', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'JOIN', 'ORDER', 'GROUP', 'LIMIT', 'FOR'])

  for (const match of fromPart.matchAll(/(?:FROM|JOIN)\s+`?((?:\w+\.)?\w+)`?(?:\s+(?:AS\s+)?`?(\w+)`?)?/gi)) {
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

function validateSelectList(file, source, errors) {
  for (const match of source.matchAll(/SELECT\s+([\s\S]*?)\s+FROM\s+([\s\S]*?)(?:\s+WHERE\b|\s+ORDER\s+BY\b|\s+GROUP\s+BY\b|\s+LIMIT\b|\s+FOR\s+UPDATE\b|"""\)|"\)|$)/gi)) {
    const [, selectPart, fromPart] = match
    const aliases = parseTableRefs(`FROM ${fromPart}`)
    const uniqueTables = new Set(aliases.values())

    for (const table of uniqueTables) {
      tableColumns(file, table, errors)
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
          errors.push(`${file}: SELECT references unknown table alias ${alias}`)
          continue
        }
        validateColumn(file, table, column, 'SELECT', errors)
        continue
      }

      const unqualified = expression.match(/^`?([a-zA-Z][a-zA-Z0-9_]*)`?(?:\s+AS\s+\w+)?$/i)
      if (unqualified && uniqueTables.size === 1) {
        validateColumn(file, [...uniqueTables][0], unqualified[1], 'SELECT', errors)
      }
    }
  }
}

const mapperFiles = walk('backend/src/main/java/com/xiyiyun/shop/persistence/mapper').filter((file) => file.endsWith('.java'))
const errors = []

for (const file of mapperFiles) {
  const source = fs.readFileSync(file, 'utf8')
  validateInsert(file, source, errors)
  validateUpdate(file, source, errors)
  validateUpsertUpdate(file, source, errors)
  validateDelete(file, source, errors)
  validateAlter(file, source, errors)
  validateSelectList(file, source, errors)
}

if (errors.length) {
  console.error([...new Set(errors)].join('\n'))
  process.exit(1)
}

console.log('schema-check: mapper SQL tables and columns match db/init/001_schema.sql')
