-- =============================================================================
-- 007_config_tables_extraction.sql
-- 批次7 / 任务A：把 9 个挤在 system_settings KV/JSON 里的业务字段迁到独立表
-- =============================================================================
-- 背景
--   system_settings 是 (setting_key, setting_value TEXT) 的 KV 表，本该只放站点
--   开关类配置。演进过程中它被当成万能仓库，塞进了 7 类**业务实体**：
--     price.templates          → 价格模板列表（JSON 数组）
--     payment.channels         → 支付通道列表（JSON 数组）
--     admin.staff.accounts     → 后台员工账号 + 口令哈希（JSON 数组）
--     admin.super.{username,passwordHash,nickname} → 超管凭据（3 个标量 key）
--     sms.login.setting        → 短信登录配置（JSON 对象）
--     captcha.setting          → 图形验证配置（JSON 对象）
--     user.password.{userId}   → 会员登录口令哈希（每人一行）
--     member.credential.{id}   → 会员开放 API 凭据（每人一行 JSON 对象）
--   外加批次6 临时寄放的 product.monitor.state.{channelId}（扫描调度状态）。
--
--   代价是实打实的：口令哈希无法建唯一键/外键、无法按用户查询；支付通道改一条要
--   整表反序列化再整体写回（读改写竞态）；员工账号唯一性只靠内存 Map 兜；监控状态
--   删除只能"写空串当墓碑"，因为 KV mapper 没有 DELETE。
--
-- 本脚本做三件事
--   1. 建立/补齐 8 张独立表（见下），并给已存在的 member_api_credentials 补上
--      密文列（原表只有明文 app_secret，应用侧从来没写过它）。
--   2. 把 system_settings 里的现有数据**搬进**新表（INSERT ... SELECT +
--      JSON_TABLE / JSON_EXTRACT 就地解析，不依赖任何应用代码）。
--   3. 全程幂等：任意次数重复执行都不报错、不产生重复行、不覆盖割接后的新写入。
--
-- 为什么不 DROP / DELETE system_settings 的老行
--   迁移一旦发布就无法回滚。老 KV 行是唯一的回滚证据：若新表读写出问题需要把代码
--   回退到批次6，老行还在，系统立刻能继续跑；行一删，回退等于数据丢失。
--   同时 ConfigService 的读路径保留"新表无行才回落老 KV"的一次性兜底（详见该类
--   注释），老行必须存在这条兜底才有意义。
--   清理动作留给后续独立变更（确认线上稳定运行、且新表已被完整备份之后再执行）。
--
-- 幂等性的实现方式（逐表说明）
--   * 建表统一 CREATE TABLE IF NOT EXISTS；加列/加索引走 add_column_if_missing /
--     add_index_if_missing（沿用 002-006 的存储过程模式），脚本末尾删除过程。
--   * 搬迁语句统一形如
--       INSERT INTO 新表 (...) SELECT ... FROM system_settings ...
--       ON DUPLICATE KEY UPDATE <某列> = <某列>
--     ODKU 写成自赋值 = 显式 no-op：**第二次执行时命中唯一键就什么都不做**，
--     因此既幂等，又不会把割接后应用写入的新值覆盖回老 KV 里的旧值
--     （这一点对 user_credentials 尤其关键：覆盖等于把改过的密码退回旧密码）。
--   * 时间戳来自 OffsetDateTime.toString()（如 2026-07-27T11:00:00.123+08:00 或
--     ...Z）。MySQL 8.0.19+ 的 CAST(... AS DATETIME(3)) 认识 [+-]hh:mm 偏移并
--     换算到会话时区，但不认 'Z'，故统一 REPLACE('T',' ') + REPLACE('Z','+00:00')
--     再 CAST。空串先 NULLIF 成 NULL，避免 STRICT 模式报错。
--     会话时区与 JDBC 的 serverTimezone=Asia/Shanghai 一致，故与应用写入语义相同。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. user_credentials —— 会员登录口令哈希（原 user.password.{userId}）
--    一个用户一行，user_id 唯一。只存 bcrypt 哈希，不存任何明文。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_credentials (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_credentials_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2. admin_staff —— 后台员工账号（原 admin.staff.accounts）
--    permissions 是权限码数组，用 JSON 列而非另开一张关联表：它整体读写、从不
--    单独按权限码查询，拆表只会增加一次 join 而没有收益。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_staff (
  id BIGINT UNSIGNED PRIMARY KEY,
  account VARCHAR(64) NOT NULL,
  nickname VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  permissions JSON NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_admin_staff_account (account),
  KEY idx_admin_staff_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 3. admin_super_credentials —— 超管凭据（原 admin.super.* 三个标量 key）
--    单行表（id 固定为 1）。为什么不塞进 admin_staff：超管不是员工列表的一员，
--    没有 permissions 概念、不参与员工分页与增删，且 id 空间由 001 之外的逻辑
--    分配；混表会让 admin_staff 的每个查询都要额外排除超管行。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_super_credentials (
  id TINYINT UNSIGNED PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  nickname VARCHAR(64) NULL,
  password_hash VARCHAR(255) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 4. payment_channels —— 支付通道（原 payment.channels）
--    config_public / config_secrets 分两列：前者是可见配置（app_id 等），后者是
--    CardCipherService 加密信封（{key: {ciphertext,nonce,keyVersion,hash}}）。
--    分列的意义在于「明文配置」与「密文信封」不会再被同一段代码不分辨地读写。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_channels (
  id BIGINT UNSIGNED PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  channel_type VARCHAR(32) NOT NULL,
  terminals JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  sort_no INT NOT NULL DEFAULT 0,
  config_public JSON NULL,
  config_secrets JSON NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_payment_channels_code (code),
  KEY idx_payment_channels_status_sort (status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 5. price_templates —— 价格模板（原 price.templates）
--    主键是业务侧自带的字符串模板 id（形如 tpl-xxx），不另造自增列：应用一直用
--    这个 id 作标识，另造代理键只会多一层映射。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_templates (
  template_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  adjust_mode VARCHAR(32) NOT NULL DEFAULT 'PERCENT',
  reference_price DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  group_rates JSON NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  sort_no INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_price_templates_enabled_sort (enabled, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 6. sms_login_settings —— 短信登录配置（原 sms.login.setting），单行表
--    标量开关拿到独立列（可被 SQL 直接观测/校验），三家 provider 的配置各自
--    public/secrets 两列，语义同 payment_channels。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sms_login_settings (
  id TINYINT UNSIGNED PRIMARY KEY,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  admin_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  h5_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  web_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  provider VARCHAR(32) NOT NULL DEFAULT 'MOCK',
  admin_mobile VARCHAR(32) NULL,
  code_length INT NOT NULL DEFAULT 6,
  ttl_seconds INT NOT NULL DEFAULT 300,
  cooldown_seconds INT NOT NULL DEFAULT 60,
  max_attempts INT NOT NULL DEFAULT 5,
  generic_config_public JSON NULL,
  generic_config_secrets JSON NULL,
  tencent_config_public JSON NULL,
  tencent_config_secrets JSON NULL,
  aliyun_config_public JSON NULL,
  aliyun_config_secrets JSON NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 7. captcha_settings —— 图形验证配置（原 captcha.setting），单行表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS captcha_settings (
  id TINYINT UNSIGNED PRIMARY KEY,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  admin_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  h5_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  web_login_enabled TINYINT(1) NOT NULL DEFAULT 0,
  provider VARCHAR(32) NOT NULL DEFAULT 'TENCENT',
  tencent_config_public JSON NULL,
  tencent_config_secrets JSON NULL,
  turnstile_config_public JSON NULL,
  turnstile_config_secrets JSON NULL,
  generic_config_public JSON NULL,
  generic_config_secrets JSON NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 8. product_monitor_states —— 商品监控扫描状态（原 product.monitor.state.{id}）
--    批次6 因"本批次不新增迁移"把它临时寄放在 KV，并用**写空串当墓碑**代替删除
--    （KV mapper 只有 upsert）。本表让删除变成真正的 DELETE，墓碑随之取消。
--    channel_id 直接做主键：一个渠道至多一条调度状态。
--    scanning 不落库：它是进程内瞬时标记，重启后必须归 false，否则该渠道永远
--    不再被判定到期（等于监控静默停摆）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_monitor_states (
  channel_id BIGINT UNSIGNED PRIMARY KEY,
  last_scan_at DATETIME(3) NULL,
  next_scan_at DATETIME(3) NULL,
  last_result VARCHAR(32) NOT NULL DEFAULT 'WAITING',
  last_message VARCHAR(500) NULL,
  scan_count INT UNSIGNED NOT NULL DEFAULT 0,
  change_count INT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_product_monitor_next_scan (next_scan_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 幂等 DDL 辅助过程（沿用 002-006 的写法）
-- =============================================================================
DELIMITER $

DROP PROCEDURE IF EXISTS add_column_if_missing $
CREATE PROCEDURE add_column_if_missing(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = target_table AND COLUMN_NAME = target_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $

DROP PROCEDURE IF EXISTS add_index_if_missing $
CREATE PROCEDURE add_index_if_missing(
  IN target_table VARCHAR(64),
  IN target_index VARCHAR(64),
  IN index_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = target_table AND INDEX_NAME = target_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD ', index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $

DROP PROCEDURE IF EXISTS modify_column_if_not_nullable $
CREATE PROCEDURE modify_column_if_not_nullable(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND COLUMN_NAME = target_column
      AND IS_NULLABLE = 'NO'
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` MODIFY COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $

DELIMITER ;

-- -----------------------------------------------------------------------------
-- 9. member_api_credentials 补齐（表在 001 已存在，但应用从未写过它）
--    001 只有明文 app_secret 一列；应用侧一直把凭据写成 member.credential.{id}
--    的 JSON，其中 app_secret 只留掩码 + CardCipherService 密文信封。
--    因此这里补密文四列 + 掩码列 + updated_at，并把明文列放宽为 NULL：
--    NOT NULL -> NULL 是放宽约束，不会因已有数据失败，也不丢数据。
--    明文列保留（不 DROP）：001 里的既有 fixture 行仍靠它，且删列同样不可回滚。
-- -----------------------------------------------------------------------------
CALL add_column_if_missing('member_api_credentials', 'app_secret_masked',
  'app_secret_masked VARCHAR(255) NULL AFTER app_secret') ;
CALL add_column_if_missing('member_api_credentials', 'app_secret_ciphertext',
  'app_secret_ciphertext TEXT NULL AFTER app_secret_masked') ;
CALL add_column_if_missing('member_api_credentials', 'app_secret_nonce',
  'app_secret_nonce VARCHAR(128) NULL AFTER app_secret_ciphertext') ;
CALL add_column_if_missing('member_api_credentials', 'app_secret_key_version',
  'app_secret_key_version VARCHAR(32) NULL AFTER app_secret_nonce') ;
CALL add_column_if_missing('member_api_credentials', 'app_secret_hash',
  'app_secret_hash CHAR(64) NULL AFTER app_secret_key_version') ;
CALL add_column_if_missing('member_api_credentials', 'updated_at',
  'updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at') ;
CALL modify_column_if_not_nullable('member_api_credentials', 'app_secret',
  'app_secret VARCHAR(255) NULL') ;

-- 一个会员至多一份 API 凭据（memberCredentialForUser / saveMemberCredential 的
-- 既有语义就是 1:1）。加唯一键前先按 user_id 去重、保留 id 最大的一行：
-- 该表在本迁移之前**从未被应用写入过**，库里只可能有 001/fixture 造的演示行，
-- 因此这次去重不会碰到任何真实业务数据；写成幂等语句，重复执行是空操作。
DELETE stale FROM member_api_credentials stale
JOIN (
  SELECT user_id, MAX(id) AS keep_id
  FROM member_api_credentials
  GROUP BY user_id
  HAVING COUNT(*) > 1
) dup ON dup.user_id = stale.user_id AND stale.id < dup.keep_id;

CALL add_index_if_missing('member_api_credentials', 'uk_member_api_user',
  'UNIQUE KEY uk_member_api_user (user_id)') ;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS modify_column_if_not_nullable;

-- =============================================================================
-- 数据搬迁：system_settings -> 新表
--
-- 两层幂等，缺一不可：
--
-- (1) 每条语句都是 ON DUPLICATE KEY UPDATE <col> = <col>（显式 no-op）。
--     命中唯一键就什么都不改，所以绝不会覆盖割接后应用写入的新值。
--
-- (2) 每条语句额外带「割接标记尚不存在」这个前置条件（见文末第 10 节）。
--     为什么 (1) 不够：ODKU 只能保护**还存在**的行。如果管理员在割接后删掉了
--     某个后台员工 / 支付通道 / 价格模板，再跑一遍 007 时那一行不再冲突，
--     会被原样重新插回去——已删除的后台账号连口令哈希一起复活成能登录的
--     僵尸账号。加上标记判断后，「搬迁」这件事整体只发生一次：
--       · 老库（无标记）：搬迁执行，末尾写标记；
--       · 再跑一遍（有标记）：9.1-9.9 全部跳过，行数不变；
--       · 全新库（001 已写标记）：同样跳过，本来也没有老 KV 要搬。
--     若首次执行中途失败，标记不会写上，重跑会带着 (1) 的保护继续补齐。
-- =============================================================================

-- 9.1 user.password.{userId} -> user_credentials
--     只搬 setting_key 后缀是纯数字、且 value 非空的行。
INSERT INTO user_credentials (user_id, password_hash)
SELECT CAST(SUBSTRING(s.setting_key, 15) AS UNSIGNED) AS user_id,
       s.setting_value AS password_hash
FROM system_settings s
WHERE s.setting_key LIKE 'user.password.%'
  AND SUBSTRING(s.setting_key, 15) REGEXP '^[0-9]+$'
  AND s.setting_value IS NOT NULL
  AND s.setting_value <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE password_hash = user_credentials.password_hash;

-- 9.2 admin.staff.accounts -> admin_staff
--     JSON 数组展开。account 为空或 passwordHash 为空的条目跳过（与
--     loadAdminStaff 的过滤条件一致：这类条目在应用里本来也不会被装载）。
INSERT INTO admin_staff (id, account, nickname, status, permissions, password_hash, created_at, updated_at)
SELECT j.id,
       j.account,
       NULLIF(j.nickname, ''),
       COALESCE(NULLIF(j.status, ''), 'ENABLED'),
       j.permissions,
       j.password_hash,
       COALESCE(CAST(REPLACE(REPLACE(NULLIF(j.created_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)), CURRENT_TIMESTAMP(3)),
       COALESCE(CAST(REPLACE(REPLACE(NULLIF(j.updated_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)), CURRENT_TIMESTAMP(3))
FROM (
  SELECT setting_value FROM system_settings
  WHERE setting_key = 'admin.staff.accounts'
    AND setting_value IS NOT NULL AND setting_value <> '' AND JSON_VALID(setting_value)
  LIMIT 1
) src
JOIN JSON_TABLE(src.setting_value, '$[*]' COLUMNS (
  id BIGINT UNSIGNED PATH '$.id',
  account VARCHAR(64) PATH '$.account',
  nickname VARCHAR(64) PATH '$.nickname',
  status VARCHAR(32) PATH '$.status',
  permissions JSON PATH '$.permissions',
  password_hash VARCHAR(255) PATH '$.passwordHash',
  created_at VARCHAR(64) PATH '$.createdAt',
  updated_at VARCHAR(64) PATH '$.updatedAt'
)) j
WHERE j.id IS NOT NULL
  AND j.account IS NOT NULL AND j.account <> ''
  AND j.password_hash IS NOT NULL AND j.password_hash <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE password_hash = admin_staff.password_hash;

-- 9.3 admin.super.{username,passwordHash,nickname} -> admin_super_credentials
--     三个标量 key 透视成一行。username / passwordHash 缺一不可（缺了这行没意义，
--     应用会继续用启动参数里的默认超管，与迁移前行为一致）。
INSERT INTO admin_super_credentials (id, username, nickname, password_hash)
SELECT 1,
       src.username,
       NULLIF(src.nickname, ''),
       src.password_hash
FROM (
  SELECT MAX(CASE WHEN setting_key = 'admin.super.username' THEN setting_value END) AS username,
         MAX(CASE WHEN setting_key = 'admin.super.nickname' THEN setting_value END) AS nickname,
         MAX(CASE WHEN setting_key = 'admin.super.passwordHash' THEN setting_value END) AS password_hash
  FROM system_settings
  WHERE setting_key IN ('admin.super.username', 'admin.super.nickname', 'admin.super.passwordHash')
) src
WHERE src.username IS NOT NULL AND src.username <> ''
  AND src.password_hash IS NOT NULL AND src.password_hash <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE password_hash = admin_super_credentials.password_hash;

-- 9.4 payment.channels -> payment_channels
--     config / configSecrets 分别落 config_public / config_secrets。
INSERT INTO payment_channels
  (id, code, name, channel_type, terminals, status, sort_no, config_public, config_secrets, remark, created_at, updated_at)
SELECT j.id,
       j.code,
       COALESCE(NULLIF(j.name, ''), '支付通道'),
       COALESCE(NULLIF(j.channel_type, ''), 'OTHER'),
       j.terminals,
       COALESCE(NULLIF(j.status, ''), 'ENABLED'),
       COALESCE(j.sort_no, 0),
       j.config_public,
       j.config_secrets,
       NULLIF(j.remark, ''),
       COALESCE(CAST(REPLACE(REPLACE(NULLIF(j.created_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)), CURRENT_TIMESTAMP(3)),
       COALESCE(CAST(REPLACE(REPLACE(NULLIF(j.updated_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)), CURRENT_TIMESTAMP(3))
FROM (
  SELECT setting_value FROM system_settings
  WHERE setting_key = 'payment.channels'
    AND setting_value IS NOT NULL AND setting_value <> '' AND JSON_VALID(setting_value)
  LIMIT 1
) src
JOIN JSON_TABLE(src.setting_value, '$[*]' COLUMNS (
  id BIGINT UNSIGNED PATH '$.id',
  code VARCHAR(64) PATH '$.code',
  name VARCHAR(128) PATH '$.name',
  channel_type VARCHAR(32) PATH '$.type',
  terminals JSON PATH '$.terminals',
  status VARCHAR(32) PATH '$.status',
  sort_no INT PATH '$.sort',
  config_public JSON PATH '$.config',
  config_secrets JSON PATH '$.configSecrets',
  remark VARCHAR(500) PATH '$.remark',
  created_at VARCHAR(64) PATH '$.createdAt',
  updated_at VARCHAR(64) PATH '$.updatedAt'
)) j
WHERE j.id IS NOT NULL
  AND j.code IS NOT NULL AND j.code <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE code = payment_channels.code;

-- 9.5 price.templates -> price_templates
--     用 FOR ORDINALITY 保留 JSON 数组里的原始顺序（模板列表是有序展示的）。
INSERT INTO price_templates
  (template_id, name, adjust_mode, reference_price, group_rates, enabled, sort_no)
SELECT j.template_id,
       COALESCE(NULLIF(j.name, ''), j.template_id),
       COALESCE(NULLIF(j.adjust_mode, ''), 'PERCENT'),
       COALESCE(j.reference_price, 0),
       j.group_rates,
       CASE WHEN j.enabled IS NULL THEN 1 WHEN j.enabled = 0 THEN 0 ELSE 1 END,
       j.ord * 10
FROM (
  SELECT setting_value FROM system_settings
  WHERE setting_key = 'price.templates'
    AND setting_value IS NOT NULL AND setting_value <> '' AND JSON_VALID(setting_value)
  LIMIT 1
) src
JOIN JSON_TABLE(src.setting_value, '$[*]' COLUMNS (
  ord FOR ORDINALITY,
  template_id VARCHAR(64) PATH '$.id',
  name VARCHAR(128) PATH '$.name',
  adjust_mode VARCHAR(32) PATH '$.adjustMode',
  reference_price DECIMAL(18,4) PATH '$.referencePrice',
  group_rates JSON PATH '$.groupRates',
  enabled INT PATH '$.enabled'
)) j
WHERE j.template_id IS NOT NULL AND j.template_id <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE name = price_templates.name;

-- 9.6 sms.login.setting -> sms_login_settings（单行）
INSERT INTO sms_login_settings (
  id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled,
  provider, admin_mobile, code_length, ttl_seconds, cooldown_seconds, max_attempts,
  generic_config_public, generic_config_secrets,
  tencent_config_public, tencent_config_secrets,
  aliyun_config_public, aliyun_config_secrets
)
SELECT 1,
       COALESCE(j.enabled, 0),
       COALESCE(j.admin_login_enabled, 0),
       COALESCE(j.h5_login_enabled, 0),
       COALESCE(j.web_login_enabled, 0),
       COALESCE(NULLIF(j.provider, ''), 'MOCK'),
       NULLIF(j.admin_mobile, ''),
       COALESCE(j.code_length, 6),
       COALESCE(j.ttl_seconds, 300),
       COALESCE(j.cooldown_seconds, 60),
       COALESCE(j.max_attempts, 5),
       j.generic_config_public, j.generic_config_secrets,
       j.tencent_config_public, j.tencent_config_secrets,
       j.aliyun_config_public, j.aliyun_config_secrets
FROM (
  SELECT setting_value FROM system_settings
  WHERE setting_key = 'sms.login.setting'
    AND setting_value IS NOT NULL AND setting_value <> '' AND JSON_VALID(setting_value)
  LIMIT 1
) src
JOIN JSON_TABLE(src.setting_value, '$' COLUMNS (
  enabled INT PATH '$.enabled',
  admin_login_enabled INT PATH '$.adminLoginEnabled',
  h5_login_enabled INT PATH '$.h5LoginEnabled',
  web_login_enabled INT PATH '$.webLoginEnabled',
  provider VARCHAR(32) PATH '$.provider',
  admin_mobile VARCHAR(32) PATH '$.adminMobile',
  code_length INT PATH '$.codeLength',
  ttl_seconds INT PATH '$.ttlSeconds',
  cooldown_seconds INT PATH '$.cooldownSeconds',
  max_attempts INT PATH '$.maxAttempts',
  generic_config_public JSON PATH '$.genericConfig',
  generic_config_secrets JSON PATH '$.genericConfigSecrets',
  tencent_config_public JSON PATH '$.tencentConfig',
  tencent_config_secrets JSON PATH '$.tencentConfigSecrets',
  aliyun_config_public JSON PATH '$.aliyunConfig',
  aliyun_config_secrets JSON PATH '$.aliyunConfigSecrets'
)) j
-- WHERE 不是冗余：JOIN 后若直接跟 ON DUPLICATE，MySQL 解析器会把 ON 当成
-- JOIN 的连接条件（报 syntax error near 'KEY UPDATE'）。有 WHERE 隔开才不歧义。
WHERE NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE provider = sms_login_settings.provider;

-- 9.7 captcha.setting -> captcha_settings（单行）
INSERT INTO captcha_settings (
  id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled, provider,
  tencent_config_public, tencent_config_secrets,
  turnstile_config_public, turnstile_config_secrets,
  generic_config_public, generic_config_secrets
)
SELECT 1,
       COALESCE(j.enabled, 0),
       COALESCE(j.admin_login_enabled, 0),
       COALESCE(j.h5_login_enabled, 0),
       COALESCE(j.web_login_enabled, 0),
       COALESCE(NULLIF(j.provider, ''), 'TENCENT'),
       j.tencent_config_public, j.tencent_config_secrets,
       j.turnstile_config_public, j.turnstile_config_secrets,
       j.generic_config_public, j.generic_config_secrets
FROM (
  SELECT setting_value FROM system_settings
  WHERE setting_key = 'captcha.setting'
    AND setting_value IS NOT NULL AND setting_value <> '' AND JSON_VALID(setting_value)
  LIMIT 1
) src
JOIN JSON_TABLE(src.setting_value, '$' COLUMNS (
  enabled INT PATH '$.enabled',
  admin_login_enabled INT PATH '$.adminLoginEnabled',
  h5_login_enabled INT PATH '$.h5LoginEnabled',
  web_login_enabled INT PATH '$.webLoginEnabled',
  provider VARCHAR(32) PATH '$.provider',
  tencent_config_public JSON PATH '$.tencentConfig',
  tencent_config_secrets JSON PATH '$.tencentConfigSecrets',
  turnstile_config_public JSON PATH '$.turnstileConfig',
  turnstile_config_secrets JSON PATH '$.turnstileConfigSecrets',
  generic_config_public JSON PATH '$.genericConfig',
  generic_config_secrets JSON PATH '$.genericConfigSecrets'
)) j
-- 同上：WHERE 隔开 JOIN 与 ON DUPLICATE
WHERE NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE provider = captcha_settings.provider;

-- 9.8 member.credential.{userId} -> member_api_credentials
--     唯一键是 user_id（上面刚建）。KV 里的 id 字段**不做主键使用**：它由内存
--     计数器分配，不同用户之间可能重复，拿它当主键会让第二个用户被静默丢弃。
--     故这里让 id 走 AUTO_INCREMENT，按 user_id 去重。
--     app_secret（明文列）不写入：KV 里从来只有掩码 + 密文信封，没有明文。
INSERT INTO member_api_credentials (
  user_id, app_key, app_secret, app_secret_masked,
  app_secret_ciphertext, app_secret_nonce, app_secret_key_version, app_secret_hash,
  status, ip_whitelist, daily_limit, last_used_at, created_at
)
SELECT CAST(SUBSTRING(s.setting_key, 19) AS UNSIGNED) AS user_id,
       j.app_key,
       NULL,
       NULLIF(j.app_secret_masked, ''),
       NULLIF(j.app_secret_ciphertext, ''),
       NULLIF(j.app_secret_nonce, ''),
       NULLIF(j.app_secret_key_version, ''),
       NULLIF(j.app_secret_hash, ''),
       COALESCE(NULLIF(j.status, ''), 'DISABLED'),
       j.ip_whitelist,
       COALESCE(j.daily_limit, 1000),
       CAST(REPLACE(REPLACE(NULLIF(j.last_used_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)),
       COALESCE(CAST(REPLACE(REPLACE(NULLIF(j.created_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)), CURRENT_TIMESTAMP(3))
FROM system_settings s
JOIN JSON_TABLE(
  IF(JSON_VALID(s.setting_value), s.setting_value, '{}'), '$' COLUMNS (
    app_key VARCHAR(128) PATH '$.appKey',
    app_secret_masked VARCHAR(255) PATH '$.appSecretMasked',
    app_secret_ciphertext TEXT PATH '$.appSecretCiphertext',
    app_secret_nonce VARCHAR(128) PATH '$.appSecretNonce',
    app_secret_key_version VARCHAR(32) PATH '$.appSecretKeyVersion',
    app_secret_hash CHAR(64) PATH '$.appSecretHash',
    status VARCHAR(32) PATH '$.status',
    ip_whitelist JSON PATH '$.ipWhitelist',
    daily_limit INT PATH '$.dailyLimit',
    created_at VARCHAR(64) PATH '$.createdAt',
    last_used_at VARCHAR(64) PATH '$.lastUsedAt'
  )
) j
WHERE s.setting_key LIKE 'member.credential.%'
  AND SUBSTRING(s.setting_key, 19) REGEXP '^[0-9]+$'
  AND s.setting_value IS NOT NULL AND s.setting_value <> ''
  AND j.app_key IS NOT NULL AND j.app_key <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE app_key = member_api_credentials.app_key;

-- 9.9 product.monitor.state.{channelId} -> product_monitor_states
--     批次6 的"空串墓碑"行天然被 setting_value <> '' 过滤掉：墓碑语义是"不存在"，
--     搬迁后也就不存在，与旧读路径（跳过空串）完全一致。
INSERT INTO product_monitor_states
  (channel_id, last_scan_at, next_scan_at, last_result, last_message, scan_count, change_count)
SELECT CAST(SUBSTRING(s.setting_key, 23) AS UNSIGNED) AS channel_id,
       CAST(REPLACE(REPLACE(NULLIF(j.last_scan_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)),
       CAST(REPLACE(REPLACE(NULLIF(j.next_scan_at, ''), 'T', ' '), 'Z', '+00:00') AS DATETIME(3)),
       COALESCE(NULLIF(j.last_result, ''), 'WAITING'),
       COALESCE(NULLIF(j.last_message, ''), '等待首次扫描'),
       COALESCE(j.scan_count, 0),
       COALESCE(j.change_count, 0)
FROM system_settings s
JOIN JSON_TABLE(
  IF(JSON_VALID(s.setting_value), s.setting_value, '{}'), '$' COLUMNS (
    last_scan_at VARCHAR(64) PATH '$.lastScanAt',
    next_scan_at VARCHAR(64) PATH '$.nextScanAt',
    last_result VARCHAR(32) PATH '$.lastResult',
    last_message VARCHAR(500) PATH '$.lastMessage',
    scan_count INT PATH '$.scanCount',
    change_count INT PATH '$.changeCount'
  )
) j
WHERE s.setting_key LIKE 'product.monitor.state.%'
  AND SUBSTRING(s.setting_key, 23) REGEXP '^[0-9]+$'
  AND s.setting_value IS NOT NULL AND s.setting_value <> ''
  AND NOT EXISTS (SELECT 1 FROM system_settings m WHERE m.setting_key = 'config.tables.cutover')
ON DUPLICATE KEY UPDATE last_result = product_monitor_states.last_result;

-- ---------------------------------------------------------------------------
-- 10. 切换标记：告诉应用「新表已经是权威数据源」。
--
--     为什么需要它：价格模板 / 支付渠道 / 后台员工原本是「整个 JSON 数组」，
--     语义单位是整张列表。应用读新表时若发现是空的，无法区分两种情况——
--       (a) 这套库还没跑过 007，数据只在老 KV 里；
--       (b) 跑过了，管理员后来把最后一个员工/渠道删干净了。
--     没有标记就只能一律回退读老 KV，于是 (b) 会把已删除的后台账号
--     连口令哈希一起复活成能登录的僵尸账号。有了标记，(b) 认定「空就是空」。
--
--     标记留在 system_settings 是故意的：它记录的是「迁移跑过没有」这个事实，
--     不属于任何业务表；001 建全新库时也写同一行，保证新库与升级库行为一致。
--     按行存储的键（会员口令、会员 API 凭据、超管）不依赖这个标记来做**读回退**，
--     它们按「该行是否存在」判断，粒度更细也更安全。
--
--     这一行同时是上面 9.1-9.9 的「只搬一次」闸门（见数据搬迁段的注释 (2)）：
--     所以它必须放在全部搬迁语句**之后**执行，否则第一次就把自己挡掉了。
-- ---------------------------------------------------------------------------
INSERT INTO system_settings (setting_key, setting_value)
VALUES ('config.tables.cutover', '007')
ON DUPLICATE KEY UPDATE setting_value = system_settings.setting_value;
