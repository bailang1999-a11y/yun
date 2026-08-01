-- TEST-V101 business fixture for MySQL 8.x.
--
-- Safety properties:
--   * Idempotent: every row has a fixed high-range id and/or TEST-V101 unique
--     key. Two marker-scoped UPDATEs only backfill newly added NULL columns on
--     fixture-owned rows; existing non-NULL values are never overwritten.
--   * Existing business data is preserved.
--   * The cards table is intentionally never written.
--   * Run only after db/init/001_schema.sql and all migrations through 007.

SET NAMES utf8mb4;
SET @test_v101_anchor = TIMESTAMP('2026-07-27 12:00:00');

DROP TEMPORARY TABLE IF EXISTS _test_v101_seq;
CREATE TEMPORARY TABLE _test_v101_seq (
  n INT UNSIGNED NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

INSERT INTO _test_v101_seq (n)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 360
)
SELECT n FROM seq;

DROP TEMPORARY TABLE IF EXISTS _test_v101_order_status;
CREATE TEMPORARY TABLE _test_v101_order_status (
  ordinal_no TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  order_status VARCHAR(32) NOT NULL,
  delivery_status VARCHAR(32) NOT NULL,
  is_paid TINYINT(1) NOT NULL,
  payment_status VARCHAR(32) NOT NULL,
  task_status VARCHAR(32) NULL
) ENGINE=MEMORY;

INSERT INTO _test_v101_order_status
  (ordinal_no, order_status, delivery_status, is_paid, payment_status, task_status)
VALUES
  (1,  'CREATED',        'PENDING',    0, 'PENDING', NULL),
  (2,  'UNPAID',         'PENDING',    0, 'PENDING', NULL),
  (3,  'PAYING',         'PENDING',    0, 'PENDING', NULL),
  (4,  'PAID',           'PENDING',    1, 'SUCCESS', 'PENDING'),
  (5,  'DELIVERING',     'PROCESSING', 1, 'SUCCESS', 'PROCESSING'),
  (6,  'PROCURING',      'PROCESSING', 1, 'SUCCESS', 'PROCESSING'),
  (7,  'WAITING_MANUAL', 'PROCESSING', 1, 'SUCCESS', 'MANUAL_REQUIRED'),
  (8,  'DELIVERED',      'DELIVERED',  1, 'SUCCESS', 'SUCCESS'),
  (9,  'FAILED',         'FAILED',     1, 'FAILED',  'FAILED'),
  (10, 'REFUNDING',      'PENDING',    1, 'SUCCESS', NULL),
  (11, 'REFUNDED',       'PENDING',    1, 'SUCCESS', NULL),
  (12, 'CANCELLED',      'PENDING',    0, 'CLOSED',  NULL),
  (13, 'CLOSED',         'PENDING',    0, 'CLOSED',  NULL);

START TRANSACTION;

-- Terminal platforms used by fixture orders and goods policies. If a real row
-- already owns one of these codes, INSERT IGNORE preserves it and later joins
-- resolve that existing id by platform_code.
INSERT IGNORE INTO sales_platforms
  (id, platform_code, platform_name, platform_type, status, sort_no, config)
VALUES
  (91000001, 'h5',  '移动 H5', 'SELF', 'NORMAL', 1, JSON_OBJECT('fixture', 'TEST-V101')),
  (91000002, 'web', 'Web 商城', 'SELF', 'NORMAL', 2, JSON_OBJECT('fixture', 'TEST-V101')),
  (91000003, 'api', '会员 API', 'API',  'NORMAL', 3, JSON_OBJECT('fixture', 'TEST-V101')),
  (91000004, 'pc',  'PC 商城',  'SELF', 'NORMAL', 4, JSON_OBJECT('fixture', 'TEST-V101'));

-- Eight independent category branches, each exactly five levels deep.
INSERT IGNORE INTO categories
  (id, parent_id, name, icon, sort_no, status, created_at, updated_at)
VALUES
  (91001001, NULL,     'TEST-V101 数字权益',       'film',     9101, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001002, 91001001, 'TEST-V101 影音会员',       'film',     9102, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001003, 91001002, 'TEST-V101 月卡专区',       'badge',    9103, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001004, 91001003, 'TEST-V101 自动发货',       'rocket',   9104, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001005, 91001004, 'TEST-V101 标准库存',       'bag',      9105, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001006, NULL,     'TEST-V101 游戏充值',       'gamepad',  9111, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001007, 91001006, 'TEST-V101 手游点券',       'gamepad',  9112, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001008, 91001007, 'TEST-V101 区服商品',       'badge',    9113, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001009, 91001008, 'TEST-V101 API 直充',       'rocket',   9114, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001010, 91001009, 'TEST-V101 秒充专区',       'flame',    9115, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001011, NULL,     'TEST-V101 人工服务',       'business', 9121, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001012, 91001011, 'TEST-V101 海外账号',       'business', 9122, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001013, 91001012, 'TEST-V101 资料核验',       'badge',    9123, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001014, 91001013, 'TEST-V101 人工代办',       'heart',    9124, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001015, 91001014, 'TEST-V101 工单履约',       'monitor',  9125, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001016, NULL,     'TEST-V101 话费流量',       'phone',    9131, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001017, 91001016, 'TEST-V101 国内通信',       'phone',    9132, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001018, 91001017, 'TEST-V101 运营商专区',     'signal',   9133, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001019, 91001018, 'TEST-V101 手机充值',       'wallet',   9134, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001020, 91001019, 'TEST-V101 小额边界',       'badge',    9135, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001021, NULL,     'TEST-V101 生活缴费',       'house',    9141, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001022, 91001021, 'TEST-V101 公共事业',       'house',    9142, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001023, 91001022, 'TEST-V101 城市服务',       'map',      9143, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001024, 91001023, 'TEST-V101 账单代缴',       'receipt',  9144, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001025, 91001024, 'TEST-V101 大额边界',       'wallet',   9145, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001026, NULL,     'TEST-V101 软件授权',       'code',     9151, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001027, 91001026, 'TEST-V101 桌面软件',       'monitor',  9152, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001028, 91001027, 'TEST-V101 专业工具',       'wrench',   9153, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001029, 91001028, 'TEST-V101 激活码',         'key',      9154, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001030, 91001029, 'TEST-V101 多端授权',       'laptop',   9155, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001031, NULL,     'TEST-V101 教育培训',       'book',     9161, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001032, 91001031, 'TEST-V101 在线课程',       'book',     9162, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001033, 91001032, 'TEST-V101 语言学习',       'languages',9163, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001034, 91001033, 'TEST-V101 课程兑换',       'ticket',   9164, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001035, 91001034, 'TEST-V101 Unicode 🚀',     'sparkles', 9165, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001036, NULL,     'TEST-V101 企业服务',       'building', 9171, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001037, 91001036, 'TEST-V101 协作办公',       'users',    9172, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001038, 91001037, 'TEST-V101 云端订阅',       'cloud',    9173, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001039, 91001038, 'TEST-V101 企业席位',       'briefcase',9174, 'ON_SALE', @test_v101_anchor, @test_v101_anchor),
  (91001040, 91001039, 'TEST-V101 批量采购',       'boxes',    9175, 'ON_SALE', @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO user_groups
  (id, name, description, is_default, status, order_enabled,
   real_name_required_for_order, price_limit_enabled, price_limit_notice,
   created_at, updated_at)
VALUES
  (91030001, 'TEST-V101 基础组', '测试基础会员', 0, 'ENABLED', 1, 0, 1, NULL, @test_v101_anchor, @test_v101_anchor),
  (91030002, 'TEST-V101 VIP组',  '测试 VIP 会员', 0, 'ENABLED', 1, 0, 1, NULL, @test_v101_anchor, @test_v101_anchor),
  (91030003, 'TEST-V101 实名组', '测试实名限制', 0, 'ENABLED', 1, 1, 1, NULL, @test_v101_anchor, @test_v101_anchor),
  (91030004, 'TEST-V101 限价组', '测试限价限制', 0, 'ENABLED', 1, 0, 0, 'TEST-V101 限价商品不可购买', @test_v101_anchor, @test_v101_anchor),
  (91030005, 'TEST-V101 禁止下单组', '测试下单权限关闭', 0, 'ENABLED', 0, 0, 1, NULL, @test_v101_anchor, @test_v101_anchor),
  (91030006, 'TEST-V101 停用组', '测试停用会员组', 0, 'DISABLED', 0, 0, 1, NULL, @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO group_goods_rules
  (id, group_id, rule_type, target_id, target_code, permission, created_at, updated_at)
VALUES
  (91060001, 91030001, 'CATEGORY', 91001001, NULL,      'ALLOW', @test_v101_anchor, @test_v101_anchor),
  (91060002, 91030001, 'PLATFORM', NULL,     'private', 'ALLOW', @test_v101_anchor, @test_v101_anchor),
  (91060003, 91030002, 'CATEGORY', 91001006, NULL,      'ALLOW', @test_v101_anchor, @test_v101_anchor),
  (91060004, 91030002, 'PLATFORM', NULL,     'douyin',  'ALLOW', @test_v101_anchor, @test_v101_anchor),
  (91060005, 91030003, 'CATEGORY', 91001011, NULL,      'ALLOW', @test_v101_anchor, @test_v101_anchor),
  (91060006, 91030003, 'PLATFORM', NULL,     'xianyu',  'DENY',  @test_v101_anchor, @test_v101_anchor),
  (91060007, 91030004, 'CATEGORY', 91001003, NULL,      'DENY',  @test_v101_anchor, @test_v101_anchor),
  (91060008, 91030004, 'PLATFORM', NULL,     'api',     'ALLOW', @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO recharge_fields
  (id, code, label, placeholder, help_text, input_type, is_required,
   sort_no, enabled, created_at, updated_at)
VALUES
  (91050001, 'test_v101_account', 'TEST-V101 充值账号', '请输入测试账号', 'TEST-V101 fixture 字段', 'text', 1, 9101, 1, @test_v101_anchor, @test_v101_anchor),
  (91050002, 'test_v101_server',  'TEST-V101 区服',     '请输入测试区服', 'TEST-V101 fixture 字段', 'text', 1, 9102, 1, @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO suppliers
  (id, name, platform_type, base_url, app_key, app_secret_masked, user_id,
   app_id, api_key, api_key_masked, callback_url, timeout_seconds, balance,
   status, remark, last_sync_at, created_at, updated_at)
VALUES
  (91040001, 'TEST-V101 供应商 A', 'FANCHEN',   'http://127.0.0.1:9/test-v101-a', 'TEST-V101-A', '****V101A', 'fixture-a', 'fixture-a', NULL, '未配置', NULL, 15, 50000, 'ENABLED',  '仅允许回环地址，禁止真实外呼', @test_v101_anchor, @test_v101_anchor, @test_v101_anchor),
  (91040002, 'TEST-V101 供应商 B', 'JINGZHAO',  'http://127.0.0.1:9/test-v101-b', 'TEST-V101-B', '****V101B', 'fixture-b', 'fixture-b', NULL, '未配置', NULL, 20, 30000, 'ENABLED',  '仅允许回环地址，禁止真实外呼', @test_v101_anchor, @test_v101_anchor, @test_v101_anchor),
  (91040003, 'TEST-V101 供应商 C', 'CHENGQUAN', 'http://127.0.0.1:9/test-v101-c', 'TEST-V101-C', '****V101C', 'fixture-c', 'fixture-c', NULL, '未配置', NULL, 25, 10000, 'DISABLED', '仅允许回环地址，禁止真实外呼', @test_v101_anchor, @test_v101_anchor, @test_v101_anchor),
  (91040004, 'TEST-V101 供应商 D', 'KASUSHOU',  'http://127.0.0.1:9/test-v101-d', 'TEST-V101-D', '****V101D', 'fixture-d', 'fixture-d', NULL, '未配置', NULL, 30,  8000, 'ENABLED',  '仅允许回环地址，禁止真实外呼', @test_v101_anchor, @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO card_kinds
  (id, name, type, cost, created_at, updated_at)
VALUES
  (91051001, 'TEST-V101 文本卡密', 'TEXT', 0.0100, @test_v101_anchor, @test_v101_anchor),
  (91051002, 'TEST-V101 账号密码', 'ACCOUNT_PASSWORD', 1.0000, @test_v101_anchor, @test_v101_anchor),
  (91051003, 'TEST-V101 兑换码', 'CODE', 9.9000, @test_v101_anchor, @test_v101_anchor),
  (91051004, 'TEST-V101 Unicode 卡密', 'TEXT', 99.9900, @test_v101_anchor, @test_v101_anchor),
  (91051005, 'TEST-V101 长文本卡密', 'TEXT', 999.0000, @test_v101_anchor, @test_v101_anchor),
  (91051006, 'TEST-V101 边界卡密', 'TEXT', 999999.9999, @test_v101_anchor, @test_v101_anchor);

-- 36 users, nine in each fixture user group.
INSERT IGNORE INTO users
  (id, avatar, mobile, email, nickname, group_id, balance, deposit,
   real_name_type, real_name, subject_name, certificate_no,
   verification_status, status, last_login_at, created_at, updated_at)
SELECT
  91031000 + s.n,
  NULL,
  CONCAT('19991', LPAD(s.n, 6, '0')),
  CONCAT('test-v101-user-', LPAD(s.n, 3, '0'), '@example.invalid'),
  IF(s.n = 1, 'TEST-V101 Unicode 用户 🚀', CONCAT('TEST-V101 用户 ', LPAD(s.n, 2, '0'))),
  91030001 + MOD(s.n - 1, 6),
  CASE s.n WHEN 1 THEN 0.0000 WHEN 2 THEN 0.0001 WHEN 3 THEN 999999.9999 ELSE CAST(100 + s.n * 17.25 AS DECIMAL(18,4)) END,
  CASE s.n WHEN 1 THEN 0.0000 WHEN 2 THEN 0.0001 WHEN 3 THEN 999999.9999 ELSE CAST(MOD(s.n, 6) * 25 AS DECIMAL(18,4)) END,
  CASE MOD(s.n, 4) WHEN 0 THEN 'PERSON' WHEN 1 THEN 'ENTERPRISE' ELSE 'NONE' END,
  IF(MOD(s.n, 4) = 0, CONCAT('测试用户', LPAD(s.n, 2, '0')), NULL),
  IF(MOD(s.n, 4) = 1, CONCAT('TEST-V101 企业 ', LPAD(s.n, 2, '0')), NULL),
  IF(MOD(s.n, 4) IN (0, 1), CONCAT('TEST-V101-ID-', LPAD(s.n, 4, '0')), NULL),
  ELT(MOD(s.n - 1, 4) + 1, 'UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED'),
  CASE WHEN MOD(s.n, 12) = 0 THEN 'DISABLED' WHEN MOD(s.n, 10) = 0 THEN 'FROZEN' ELSE 'NORMAL' END,
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(DAY, -s.n, @test_v101_anchor),
  TIMESTAMPADD(DAY, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
WHERE s.n <= 36;

-- All fixture users share the documented local-only password Test@123456.
INSERT IGNORE INTO system_settings (setting_key, setting_value, updated_at)
SELECT
  CONCAT('user.password.', 91031000 + s.n),
  '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG',
  @test_v101_anchor
FROM _test_v101_seq s
WHERE s.n <= 36;

-- 007 makes user_credentials authoritative. Keep the legacy KV rows above for
-- rollback coverage, and mirror the same bcrypt hashes into the extracted table.
INSERT IGNORE INTO user_credentials
  (user_id, password_hash, created_at, updated_at)
SELECT
  91031000 + s.n,
  '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG',
  @test_v101_anchor,
  @test_v101_anchor
FROM _test_v101_seq s
WHERE s.n <= 36;

-- Extracted configuration tables introduced by 007. All payment rows are
-- disabled placeholders; no fixture row can invoke a real payment provider.
INSERT IGNORE INTO admin_staff
  (id, account, nickname, status, permissions, password_hash, created_at, updated_at)
VALUES
  (92210001, 'test_v101_staff_01', 'TEST-V101 商品员工', 'ENABLED', JSON_ARRAY('dashboard:read', 'goods:manage'), '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG', @test_v101_anchor, @test_v101_anchor),
  (92210002, 'test_v101_staff_02', 'TEST-V101 订单员工', 'ENABLED', JSON_ARRAY('dashboard:read', 'orders:manage'), '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG', @test_v101_anchor, @test_v101_anchor),
  (92210003, 'test_v101_staff_03', 'TEST-V101 配置管理员', 'ENABLED', JSON_ARRAY('dashboard:read', 'users:manage', 'settings:manage', 'staff:manage'), '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG', @test_v101_anchor, @test_v101_anchor),
  (92210004, 'test_v101_staff_04', 'TEST-V101 停用员工', 'DISABLED', JSON_ARRAY('dashboard:read', 'goods:manage', 'orders:manage', 'users:manage', 'settings:manage', 'staff:manage'), '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG', @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO payment_channels
  (id, code, name, channel_type, terminals, status, sort_no,
   config_public, config_secrets, remark, created_at, updated_at)
VALUES
  (92220001, 'test_v101_balance_disabled', 'TEST-V101 余额占位', 'BALANCE', JSON_ARRAY('h5', 'web', 'api'), 'DISABLED', 9221, JSON_OBJECT('fixture', 'TEST-V101', 'mock', true), NULL, 'TEST-V101 禁用占位，禁止真实支付', @test_v101_anchor, @test_v101_anchor),
  (92220002, 'test_v101_wechat_disabled', 'TEST-V101 微信占位', 'WECHAT', JSON_ARRAY('h5', 'web'), 'DISABLED', 9222, JSON_OBJECT('fixture', 'TEST-V101', 'mock', true, 'endpoint', 'http://127.0.0.1:9/test-v101-wechat'), NULL, 'TEST-V101 禁用占位，禁止真实支付', @test_v101_anchor, @test_v101_anchor),
  (92220003, 'test_v101_alipay_disabled', 'TEST-V101 支付宝占位', 'ALIPAY', JSON_ARRAY('h5', 'web'), 'DISABLED', 9223, JSON_OBJECT('fixture', 'TEST-V101', 'mock', true, 'endpoint', 'http://127.0.0.1:9/test-v101-alipay'), NULL, 'TEST-V101 禁用占位，禁止真实支付', @test_v101_anchor, @test_v101_anchor),
  (92220004, 'test_v101_bank_disabled', 'TEST-V101 银行占位', 'BANK', JSON_ARRAY('web'), 'DISABLED', 9224, JSON_OBJECT('fixture', 'TEST-V101', 'mock', true), NULL, 'TEST-V101 禁用占位，禁止真实支付', @test_v101_anchor, @test_v101_anchor);

INSERT IGNORE INTO price_templates
  (template_id, name, adjust_mode, reference_price, group_rates, enabled,
   sort_no, created_at, updated_at)
VALUES
  ('test-v101-percent', 'TEST-V101 百分比模板', 'PERCENT', 100.0000, JSON_ARRAY(JSON_OBJECT('groupName', 'TEST-V101 基础组', 'color', '#12a594', 'value', 110), JSON_OBJECT('groupName', 'TEST-V101 VIP组', 'color', '#3aa5ff', 'value', 95)), 1, 9221, @test_v101_anchor, @test_v101_anchor),
  ('test-v101-fixed', 'TEST-V101 固定加价模板', 'FIXED', 100.0000, JSON_ARRAY(JSON_OBJECT('groupName', 'TEST-V101 基础组', 'color', '#12a594', 'value', 5), JSON_OBJECT('groupName', 'TEST-V101 VIP组', 'color', '#3aa5ff', 'value', 1)), 1, 9222, @test_v101_anchor, @test_v101_anchor),
  ('test-v101-disabled', 'TEST-V101 停用模板', 'PERCENT', 100.0000, JSON_ARRAY(JSON_OBJECT('groupName', 'TEST-V101 基础组', 'color', '#999999', 'value', 100)), 0, 9223, @test_v101_anchor, @test_v101_anchor);

-- Singleton settings use id=1 by contract. Insert a completely disabled local
-- placeholder only when the table is empty; never mutate an existing setting.
INSERT IGNORE INTO sms_login_settings
  (id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled,
   provider, admin_mobile, code_length, ttl_seconds, cooldown_seconds,
   max_attempts, generic_config_public, generic_config_secrets,
   tencent_config_public, tencent_config_secrets,
   aliyun_config_public, aliyun_config_secrets, updated_at)
SELECT
  1, 0, 0, 0, 0, 'MOCK', NULL, 6, 300, 60, 5,
  JSON_OBJECT('fixture', 'TEST-V101', 'mock', true), NULL,
  JSON_OBJECT(), NULL, JSON_OBJECT(), NULL, @test_v101_anchor
WHERE NOT EXISTS (SELECT 1 FROM sms_login_settings WHERE id = 1);

INSERT IGNORE INTO captcha_settings
  (id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled,
   provider, tencent_config_public, tencent_config_secrets,
   turnstile_config_public, turnstile_config_secrets,
   generic_config_public, generic_config_secrets, updated_at)
SELECT
  1, 0, 0, 0, 0, 'ALTCHA', JSON_OBJECT(), NULL, JSON_OBJECT(), NULL,
  JSON_OBJECT('fixture', 'TEST-V101', 'mock', true), NULL, @test_v101_anchor
WHERE NOT EXISTS (SELECT 1 FROM captcha_settings WHERE id = 1);

-- 180 goods, distributed evenly across CARD, DIRECT and MANUAL types and the
-- three level-five leaf categories. Fifteen rows are OFF_SALE and fifteen are
-- SOLD_OUT; the remaining 150 are ON_SALE.
INSERT IGNORE INTO goods
  (id, category_id, name, goods_type, status, face_value, sale_price,
   cost_price, stock_mode, stock_count, min_qty, max_qty, delivery_template,
   sort_no, description, created_at, updated_at)
SELECT
  91100000 + s.n,
  ELT(MOD(s.n - 1, 8) + 1, 91001005, 91001010, 91001015, 91001020, 91001025, 91001030, 91001035, 91001040),
  CONCAT(
    'TEST-V101 ',
    CASE MOD(s.n - 1, 3) WHEN 0 THEN '卡密权益 ' WHEN 1 THEN '游戏直充 ' ELSE '人工代办 ' END,
    LPAD(s.n, 3, '0')
  ),
  CASE MOD(s.n - 1, 3) WHEN 0 THEN 'CARD' WHEN 1 THEN 'DIRECT' ELSE 'MANUAL' END,
  CASE WHEN MOD(s.n, 12) = 0 THEN 'SOLD_OUT' WHEN MOD(s.n, 11) = 0 THEN 'OFF_SALE' ELSE 'ON_SALE' END,
  IF(MOD(s.n, 17) = 0, NULL, CAST(10 + s.n AS DECIMAL(18,4))),
  CASE s.n WHEN 1 THEN 0.0100 WHEN 2 THEN 999999.9999 ELSE CAST(4.90 + s.n * 0.73 AS DECIMAL(18,4)) END,
  IF(MOD(s.n, 19) = 0, NULL, CAST(3.10 + s.n * 0.51 AS DECIMAL(18,4))),
  IF(MOD(s.n - 1, 3) = 1, 'REMOTE', 'LOCAL'),
  CASE s.n WHEN 2 THEN 1 WHEN 3 THEN 2 WHEN 5 THEN 999999 ELSE IF(MOD(s.n, 12) = 0, 0, 20 + MOD(s.n * 7, 480)) END,
  1,
  1 + MOD(s.n, 9),
  JSON_OBJECT(
    'subTitle', CONCAT('TEST-V101 商品规格 ', LPAD(s.n, 3, '0')),
    'benefitDurations', JSON_ARRAY(ELT(MOD(s.n - 1, 4) + 1, '周卡', '月卡', '季卡', '一年')),
    'benefitType', ELT(MOD(s.n - 1, 3) + 1, 'VIP', '点券', '人工服务'),
    'benefitBrand', ELT(MOD(s.n - 1, 4) + 1, '测试品牌A', '测试品牌B', '测试品牌C', '测试品牌D'),
    'priceLimited', IF(MOD(s.n, 10) = 0, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$')),
    'priceLimitText', IF(MOD(s.n, 10) = 0, CONCAT('不低于 ', FORMAT(4.90 + s.n * 0.73, 2), ' 元'), ''),
    'coverUrl', '',
    'detailImages', JSON_ARRAY(),
    'detailBlocks', JSON_ARRAY(JSON_OBJECT('type', 'text', 'imageUrl', '', 'text', CONCAT('TEST-V101 商品说明 ', s.n))),
    'integrations', JSON_ARRAY(JSON_OBJECT(
      'supplierId', 91040001 + MOD(s.n - 1, 4),
      'supplierName', CONCAT('TEST-V101 供应商 ', CHAR(65 + MOD(s.n - 1, 4))),
      'supplierGoodsId', CONCAT('TEST-V101-UP-', LPAD(s.n, 4, '0')),
      'supplierGoodsName', CONCAT('Fixture upstream goods ', s.n),
      'supplierPrice', CAST(3.10 + s.n * 0.51 AS DECIMAL(18,4)),
      'upstreamStatus', IF(MOD(s.n, 12) = 0, 'SOLD_OUT', 'ON_SALE'),
      'upstreamStock', IF(MOD(s.n, 12) = 0, 0, 100 + s.n),
      'enabled', IF(MOD(s.n, 17) <> 0, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$'))
    )),
    'pollingEnabled', IF(MOD(s.n, 2) = 0, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$')),
    'monitoringEnabled', IF(MOD(s.n, 7) <> 0, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$')),
    'requireRechargeAccount', IF(MOD(s.n - 1, 3) <> 0, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$')),
    'accountTypes', IF(MOD(s.n - 1, 3) = 0, JSON_ARRAY(), JSON_ARRAY('test_v101_account', 'test_v101_server')),
    'priceTemplateId', IF(
      MOD(s.n, 5) = 0,
      ELT(MOD(s.n - 1, 3) + 1, 'test-v101-percent', 'test-v101-fixed', 'test-v101-disabled'),
      'retail-default'
    ),
    'priceMode', IF(MOD(s.n, 5) = 0, 'DYNAMIC', 'FIXED'),
    'priceCoefficient', IF(MOD(s.n, 5) = 0, 1.05, 1.00),
    'priceFixedAdd', 0,
    'tags', JSON_ARRAY('TEST-V101', ELT(MOD(s.n - 1, 3) + 1, '卡密', '直充', '人工')),
    'availablePlatforms', CASE MOD(s.n - 1, 6)
      WHEN 0 THEN JSON_ARRAY('h5')
      WHEN 1 THEN JSON_ARRAY('web')
      WHEN 2 THEN JSON_ARRAY('pc')
      WHEN 3 THEN JSON_ARRAY('api')
      WHEN 4 THEN JSON_ARRAY('h5', 'web', 'pc', 'api')
      ELSE JSON_ARRAY()
    END,
    'forbiddenPlatforms', CASE
      WHEN MOD(s.n, 15) = 0 THEN JSON_ARRAY('h5', 'api')
      WHEN MOD(s.n, 10) = 0 THEN JSON_ARRAY('xianyu')
      ELSE JSON_ARRAY()
    END,
    'cardKindId', IF(MOD(s.n - 1, 3) = 0, 91051001 + MOD(FLOOR((s.n - 1) / 3), 6), NULL)
  ),
  9200 + s.n,
  IF(
    s.n = 180,
    REPEAT('TEST-V101 长文本边界与 Unicode 🚀；', 500),
    CONCAT('TEST-V101 fixture 商品 ', s.n, '；仅用于功能、分页、库存和平台策略测试。')
  ),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
WHERE s.n <= 180;

-- One concrete upstream channel per fixture good.
INSERT IGNORE INTO goods_channels
  (id, goods_id, supplier_id, supplier_name, supplier_goods_id, priority,
   timeout_seconds, status, created_at, updated_at)
SELECT
  91150000 + s.n,
  91100000 + s.n,
  91040001 + MOD(s.n - 1, 4),
  CONCAT('TEST-V101 供应商 ', CHAR(65 + MOD(s.n - 1, 4))),
  CONCAT('TEST-V101-UP-', LPAD(s.n, 4, '0')),
  10 + MOD(s.n, 3) * 10,
  15 + MOD(s.n, 4) * 5,
  IF(MOD(s.n, 17) = 0, 'DISABLED', 'ENABLED'),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
WHERE s.n <= 180;

-- Representative persisted scheduler states for fixture-owned goods channels.
-- Keep next_scan_at in the future so the background worker cannot mutate the
-- deterministic state distribution while the fixture is being validated.
INSERT IGNORE INTO product_monitor_states
  (channel_id, last_scan_at, next_scan_at, last_result, last_message,
   scan_count, change_count, updated_at)
SELECT
  91150000 + s.n,
  TIMESTAMPADD(MINUTE, -s.n * 10, @test_v101_anchor),
  TIMESTAMPADD(MINUTE, s.n, TIMESTAMP('2099-01-01 00:00:00')),
  ELT(MOD(s.n - 1, 4) + 1, 'WAITING', 'NO_CHANGE', 'CHANGED', 'FAILED'),
  CONCAT('TEST-V101 monitor state ', LPAD(s.n, 2, '0')),
  s.n * 3,
  MOD(s.n, 4),
  @test_v101_anchor
FROM _test_v101_seq s
JOIN goods_channels gc ON gc.id = 91150000 + s.n
WHERE s.n <= 12;

INSERT IGNORE INTO goods_available_platform
  (id, goods_id, platform_id, status, created_at, updated_at)
SELECT
  91170000 + s.n,
  91100000 + s.n,
  p.id,
  'NORMAL',
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
JOIN sales_platforms p
  ON p.platform_code = ELT(MOD(s.n - 1, 4) + 1, 'h5', 'web', 'pc', 'api')
WHERE s.n <= 180;

INSERT IGNORE INTO goods_forbidden_platform
  (id, goods_id, platform_id, reason, created_at, updated_at)
SELECT
  91190000 + s.n,
  91100000 + s.n,
  p.id,
  'TEST-V101 平台禁售验证',
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
JOIN sales_platforms p ON p.platform_code = 'xianyu'
WHERE s.n <= 180 AND MOD(s.n, 10) = 0;

-- 156 orders: 13 real OrderStatus values x 12 rows each.
INSERT IGNORE INTO orders
  (id, order_no, user_id, source_platform_id, source_platform_code, goods_id,
   goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price,
   total_amount, pay_amount, cost_amount, status, delivery_status,
   delivery_message, delivery_items_json, delivery_card_ids_json,
   channel_attempts_json, recharge_account, recharge_fields_json,
   buyer_remark, admin_remark, request_id, upstream_order_no,
   paid_at, delivered_at, closed_at,
   created_at, updated_at)
SELECT
  91200000 + s.n,
  CONCAT('TEST-V101-', LPAD(s.n, 4, '0')),
  91031001 + MOD(s.n - 1, 36),
  p.id,
  p.platform_code,
  g.id,
  g.name,
  g.goods_type,
  CONCAT('203.0.113.', 1 + MOD(s.n, 250)),
  ELT(MOD(s.n - 1, 4) + 1, '上海', '北京', '广东深圳', '浙江杭州'),
  1 + MOD(s.n, 3),
  g.sale_price,
  g.sale_price * (1 + MOD(s.n, 3)),
  g.sale_price * (1 + MOD(s.n, 3)),
  g.cost_price * (1 + MOD(s.n, 3)),
  os.order_status,
  os.delivery_status,
  CASE os.order_status
    WHEN 'CREATED' THEN 'TEST-V101 订单已创建'
    WHEN 'UNPAID' THEN 'TEST-V101 等待支付'
    WHEN 'PAYING' THEN 'TEST-V101 支付确认中'
    WHEN 'PAID' THEN 'TEST-V101 已支付待履约'
    WHEN 'DELIVERING' THEN 'TEST-V101 发货处理中'
    WHEN 'PROCURING' THEN 'TEST-V101 上游采购中'
    WHEN 'WAITING_MANUAL' THEN 'TEST-V101 等待人工处理'
    WHEN 'DELIVERED' THEN 'TEST-V101 已完成交付（无卡密数据）'
    WHEN 'FAILED' THEN 'TEST-V101 履约失败'
    WHEN 'REFUNDING' THEN 'TEST-V101 退款处理中'
    WHEN 'REFUNDED' THEN 'TEST-V101 已退款'
    WHEN 'CANCELLED' THEN 'TEST-V101 用户取消'
    ELSE 'TEST-V101 订单关闭'
  END,
  IF(os.order_status = 'DELIVERED' AND g.goods_type <> 'CARD', JSON_ARRAY('TEST-V101 履约结果'), JSON_ARRAY()),
  JSON_ARRAY(),
  IF(
    g.goods_type = 'CARD',
    JSON_ARRAY(),
    JSON_ARRAY(JSON_OBJECT(
      'channelId', 91150000 + s.n,
      'supplierId', 91040001 + MOD(s.n - 1, 4),
      'supplierName', CONCAT('TEST-V101 供应商 ', CHAR(65 + MOD(s.n - 1, 4))),
      'status', CASE WHEN os.order_status IN ('FAILED', 'REFUNDING', 'REFUNDED') THEN 'FAILED' WHEN os.order_status = 'DELIVERED' THEN 'SUCCESS' ELSE 'PROCURING' END,
      'message', CONCAT('TEST-V101 channel attempt ', s.n)
    ))
  ),
  IF(g.goods_type = 'CARD', NULL, CONCAT('fixture-account-', LPAD(s.n, 4, '0'))),
  IF(g.goods_type = 'CARD', JSON_OBJECT(), JSON_OBJECT(
    'test_v101_account', CONCAT('fixture-account-', LPAD(s.n, 4, '0')),
    'test_v101_server', CONCAT('S', 1 + MOD(s.n, 12))
  )),
  CONCAT('TEST-V101 买家备注 ', s.n),
  IF(MOD(s.n, 9) = 0, 'TEST-V101 运营关注', NULL),
  CONCAT('TEST-V101-REQUEST-', LPAD(s.n, 4, '0')),
  IF(
    g.goods_type = 'DIRECT'
      AND os.order_status IN ('PROCURING', 'DELIVERED', 'FAILED', 'REFUNDING', 'REFUNDED'),
    CONCAT('TEST-V101-UPSTREAM-', LPAD(s.n, 4, '0')),
    NULL
  ),
  IF(
    os.is_paid = 1,
    IF(
      os.order_status IN ('PROCURING', 'DELIVERING'),
      TIMESTAMPADD(SECOND, s.n, TIMESTAMP('2099-01-01 00:00:00')),
      TIMESTAMPADD(MINUTE, 5, TIMESTAMPADD(MINUTE, -s.n * 75, @test_v101_anchor))
    ),
    NULL
  ),
  IF(os.order_status = 'DELIVERED', TIMESTAMPADD(MINUTE, 35, TIMESTAMPADD(MINUTE, -s.n * 75, @test_v101_anchor)), NULL),
  IF(os.order_status IN ('REFUNDED', 'CANCELLED', 'CLOSED'), TIMESTAMPADD(MINUTE, 45, TIMESTAMPADD(MINUTE, -s.n * 75, @test_v101_anchor)), NULL),
  IF(
    os.order_status IN ('CREATED', 'UNPAID'),
    TIMESTAMPADD(SECOND, s.n, TIMESTAMP('2099-01-01 00:00:00')),
    TIMESTAMPADD(MINUTE, -s.n * 75, @test_v101_anchor)
  ),
  IF(
    os.order_status IN ('CREATED', 'UNPAID'),
    TIMESTAMPADD(SECOND, s.n, TIMESTAMP('2099-01-01 00:00:00')),
    TIMESTAMPADD(MINUTE, -s.n * 75, @test_v101_anchor)
  )
FROM _test_v101_seq s
JOIN _test_v101_order_status os ON os.ordinal_no = MOD(s.n - 1, 13) + 1
JOIN goods g ON g.id = 91100000 + s.n
JOIN sales_platforms p
  ON p.platform_code = ELT(MOD(s.n - 1, 4) + 1, 'h5', 'web', 'pc', 'api')
WHERE s.n <= 156;

-- Upgrade an already-loaded pre-v1.20 fixture without touching non-fixture
-- orders or overwriting an upstream number that was set by a test.
UPDATE orders
SET upstream_order_no = CONCAT('TEST-V101-UPSTREAM-', RIGHT(order_no, 4))
WHERE id BETWEEN 91200001 AND 91200156
  AND order_no LIKE 'TEST-V101-%'
  AND request_id LIKE 'TEST-V101-REQUEST-%'
  AND goods_type = 'DIRECT'
  AND status IN ('PROCURING', 'DELIVERED', 'FAILED', 'REFUNDING', 'REFUNDED')
  AND upstream_order_no IS NULL;

-- Two offsetting ledger entries per fixture user. The chain starts and ends at
-- users.balance, so the fixture exercises CREDIT/DEBIT invariants without
-- changing the current account balance.
INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92230000 + s.n,
  u.id,
  'CREDIT',
  10.0000,
  u.balance,
  u.balance + 10.0000,
  'RECHARGE',
  CONCAT('TEST-V101-BALANCE-CREDIT-', LPAD(s.n, 4, '0')),
  'TEST-V101 fixture balance credit',
  TIMESTAMPADD(MINUTE, -2, @test_v101_anchor)
FROM _test_v101_seq s
JOIN users u ON u.id = 91031000 + s.n AND u.nickname LIKE 'TEST-V101%'
WHERE s.n <= 36;

INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92234000 + s.n,
  u.id,
  'CREDIT',
  5.0000,
  0.0000,
  5.0000,
  'ADMIN_ADJUST',
  CONCAT('TEST-V101-ADMIN-CREDIT-', LPAD(s.n, 4, '0')),
  'TEST-V101 fixture admin credit',
  @test_v101_anchor
FROM _test_v101_seq s
JOIN users u ON u.id = 91031000 + s.n AND u.nickname LIKE 'TEST-V101%'
WHERE s.n <= 36;

INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92230036 + s.n,
  u.id,
  'DEBIT',
  10.0000,
  u.balance + 10.0000,
  u.balance,
  'ADMIN_ADJUST',
  CONCAT('TEST-V101-BALANCE-DEBIT-', LPAD(s.n, 4, '0')),
  'TEST-V101 fixture balance debit',
  TIMESTAMPADD(MINUTE, -1, @test_v101_anchor)
FROM _test_v101_seq s
JOIN users u ON u.id = 91031000 + s.n AND u.nickname LIKE 'TEST-V101%'
WHERE s.n <= 36;

-- One payment row for every order after CREATED. Pending and closed statuses
-- remain represented so payment filters have realistic edge cases.
INSERT IGNORE INTO payment_records
  (id, payment_no, order_id, order_no, user_id, channel, out_trade_no,
   amount, status, channel_payload, paid_at, created_at, updated_at)
SELECT
  91300000 + s.n,
  CONCAT('TEST-V101-PAY-', LPAD(s.n, 4, '0')),
  o.id,
  o.order_no,
  o.user_id,
  ELT(MOD(s.n - 1, 3) + 1, 'balance', 'wechat', 'alipay'),
  CONCAT('TEST-V101-TRADE-', LPAD(s.n, 4, '0')),
  o.pay_amount,
  os.payment_status,
  JSON_OBJECT('fixture', 'TEST-V101', 'sequence', s.n),
  IF(os.is_paid = 1, o.paid_at, NULL),
  TIMESTAMPADD(MINUTE, 1, o.created_at),
  TIMESTAMPADD(MINUTE, 1, o.created_at)
FROM _test_v101_seq s
JOIN _test_v101_order_status os ON os.ordinal_no = MOD(s.n - 1, 13) + 1
JOIN orders o ON o.id = 91200000 + s.n
WHERE s.n <= 156 AND os.order_status <> 'CREATED';

-- Successful external payments write a CREDIT settlement fact and a matching
-- DEBIT order-payment fact. Both use the same amount and different idempotency
-- namespaces, mirroring FundsLedgerStore.settleExternalPayment().
INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92232000 + s.n,
  p.user_id,
  'CREDIT',
  p.amount,
  0.0000,
  p.amount,
  'PAYMENT_SETTLE',
  p.payment_no,
  'TEST-V101 fixture external payment settlement',
  p.created_at
FROM _test_v101_seq s
JOIN payment_records p ON p.id = 91300000 + s.n AND p.status = 'SUCCESS'
WHERE s.n <= 156;

INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92231000 + s.n,
  p.user_id,
  'DEBIT',
  p.amount,
  p.amount,
  0.0000,
  'ORDER_PAY',
  p.order_no,
  'TEST-V101 fixture order payment debit',
  p.created_at
FROM _test_v101_seq s
JOIN payment_records p ON p.id = 91300000 + s.n AND p.status = 'SUCCESS'
WHERE s.n <= 156;

INSERT IGNORE INTO payment_callback_logs
  (id, provider, payment_no, order_no, callback_status, channel_trade_no,
   result, message, raw_payload, idempotency_key, created_at)
SELECT
  91700000 + s.n,
  p.channel,
  p.payment_no,
  p.order_no,
  p.status,
  CONCAT('TEST-V101-CHANNEL-', LPAD(s.n, 4, '0')),
  CASE p.status WHEN 'SUCCESS' THEN 'SUCCESS' WHEN 'FAILED' THEN 'FAILED' ELSE 'IGNORED' END,
  CONCAT('TEST-V101 payment callback ', p.status),
  JSON_OBJECT('fixture', 'TEST-V101', 'paymentNo', p.payment_no),
  CONCAT('TEST-V101-CALLBACK-IDEM-', LPAD(s.n, 4, '0')),
  TIMESTAMPADD(MINUTE, 2, p.created_at)
FROM _test_v101_seq s
JOIN payment_records p ON p.id = 91300000 + s.n
WHERE s.n <= 156;

-- Same compatibility backfill for callback rows created before migration 006.
UPDATE payment_callback_logs
SET idempotency_key = CONCAT('TEST-V101-CALLBACK-IDEM-', RIGHT(order_no, 4))
WHERE id BETWEEN 91700001 AND 91700156
  AND order_no LIKE 'TEST-V101-%'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.fixture')) = 'TEST-V101'
  AND idempotency_key IS NULL;

-- Exactly 36 refund rows: processing, success and failed outcomes.
INSERT IGNORE INTO refund_records
  (id, refund_no, order_id, payment_id, user_id, out_refund_no, amount,
   reason, status, channel_payload, refunded_at, created_at, updated_at)
SELECT
  91400000 + s.n,
  CONCAT('TEST-V101-REFUND-', LPAD(s.n, 4, '0')),
  o.id,
  p.id,
  o.user_id,
  CONCAT('TEST-V101-OUT-REFUND-', LPAD(s.n, 4, '0')),
  o.pay_amount,
  CONCAT('TEST-V101 ', CASE os.order_status WHEN 'REFUNDED' THEN '已完成退款' WHEN 'FAILED' THEN '退款失败' ELSE '退款处理中' END),
  CASE os.order_status WHEN 'REFUNDED' THEN 'SUCCESS' WHEN 'FAILED' THEN 'FAILED' ELSE 'PROCESSING' END,
  JSON_OBJECT('fixture', 'TEST-V101', 'orderNo', o.order_no),
  IF(os.order_status = 'REFUNDED', o.closed_at, NULL),
  TIMESTAMPADD(MINUTE, 20, o.created_at),
  TIMESTAMPADD(MINUTE, 20, o.created_at)
FROM _test_v101_seq s
JOIN _test_v101_order_status os ON os.ordinal_no = MOD(s.n - 1, 13) + 1
JOIN orders o ON o.id = 91200000 + s.n
JOIN payment_records p ON p.id = 91300000 + s.n
WHERE s.n <= 156 AND os.order_status IN ('FAILED', 'REFUNDING', 'REFUNDED');

INSERT IGNORE INTO user_balance_transactions
  (id, user_id, direction, amount, balance_before, balance_after,
   biz_type, biz_no, remark, created_at)
SELECT
  92233000 + s.n,
  o.user_id,
  'CREDIT',
  o.pay_amount,
  0.0000,
  o.pay_amount,
  'ORDER_REFUND',
  o.order_no,
  'TEST-V101 fixture completed order refund',
  COALESCE(o.closed_at, o.updated_at)
FROM _test_v101_seq s
JOIN orders o ON o.id = 91200000 + s.n AND o.status = 'REFUNDED'
WHERE s.n <= 156;

-- 72 delivery tasks across PAID, DELIVERING, PROCURING, WAITING_MANUAL,
-- DELIVERED and FAILED states.
INSERT IGNORE INTO delivery_tasks
  (id, task_no, order_id, order_no, goods_id, supplier_id, delivery_type,
   status, attempt_count, next_retry_at, request_payload, response_payload,
   error_message, created_at, updated_at)
SELECT
  91500000 + s.n,
  CONCAT('TEST-V101-TASK-', LPAD(s.n, 4, '0')),
  o.id,
  o.order_no,
  o.goods_id,
  91040001 + MOD(s.n - 1, 4),
  o.goods_type,
  os.task_status,
  CASE os.task_status WHEN 'SUCCESS' THEN 1 WHEN 'FAILED' THEN 3 ELSE MOD(s.n, 3) END,
  IF(os.task_status IN ('PENDING', 'PROCESSING'), TIMESTAMPADD(MINUTE, 15, @test_v101_anchor), NULL),
  JSON_OBJECT('fixture', 'TEST-V101', 'orderNo', o.order_no),
  IF(os.task_status = 'SUCCESS', JSON_OBJECT('code', 0, 'message', 'TEST-V101 success'), NULL),
  IF(os.task_status = 'FAILED', 'TEST-V101 simulated delivery failure', NULL),
  TIMESTAMPADD(MINUTE, 6, o.created_at),
  TIMESTAMPADD(MINUTE, 6, o.created_at)
FROM _test_v101_seq s
JOIN _test_v101_order_status os ON os.ordinal_no = MOD(s.n - 1, 13) + 1
JOIN orders o ON o.id = 91200000 + s.n
WHERE s.n <= 156 AND os.task_status IS NOT NULL;

-- 156 creation logs plus 144 transitions for all non-CREATED states.
INSERT IGNORE INTO order_status_logs
  (id, order_id, order_no, from_status, to_status, operator_type,
   operator_id, remark, created_at)
SELECT
  91600000 + s.n,
  o.id,
  o.order_no,
  NULL,
  'CREATED',
  'SYSTEM',
  NULL,
  'TEST-V101 订单创建',
  o.created_at
FROM _test_v101_seq s
JOIN orders o ON o.id = 91200000 + s.n
WHERE s.n <= 156;

INSERT IGNORE INTO order_status_logs
  (id, order_id, order_no, from_status, to_status, operator_type,
   operator_id, remark, created_at)
SELECT
  91610000 + s.n,
  o.id,
  o.order_no,
  'CREATED',
  os.order_status,
  IF(os.order_status IN ('FAILED', 'REFUNDING', 'REFUNDED'), 'ADMIN', 'SYSTEM'),
  IF(os.order_status IN ('FAILED', 'REFUNDING', 'REFUNDED'), 1, NULL),
  CONCAT('TEST-V101 状态流转到 ', os.order_status),
  TIMESTAMPADD(MINUTE, 5, o.created_at)
FROM _test_v101_seq s
JOIN _test_v101_order_status os ON os.ordinal_no = MOD(s.n - 1, 13) + 1
JOIN orders o ON o.id = 91200000 + s.n
WHERE s.n <= 156 AND os.order_status <> 'CREATED';

INSERT IGNORE INTO admin_operation_logs
  (id, admin_id, admin_name, action, resource_type, resource_id, ip,
   user_agent, before_data, after_data, request_id, created_at)
SELECT
  91800000 + s.n,
  1,
  'TEST-V101 fixture',
  ELT(MOD(s.n - 1, 4) + 1, 'GOODS_UPDATE', 'ORDER_REVIEW', 'USER_REVIEW', 'SUPPLIER_SYNC'),
  ELT(MOD(s.n - 1, 4) + 1, 'GOODS', 'ORDER', 'USER', 'SUPPLIER'),
  CONCAT('TEST-V101-', LPAD(s.n, 4, '0')),
  '203.0.113.10',
  'TEST-V101 static fixture',
  JSON_OBJECT('fixture', 'TEST-V101', 'phase', 'before'),
  JSON_OBJECT('fixture', 'TEST-V101', 'phase', 'after'),
  CONCAT('TEST-V101-ADMIN-', LPAD(s.n, 4, '0')),
  TIMESTAMPADD(MINUTE, -s.n * 20, @test_v101_anchor)
FROM _test_v101_seq s
WHERE s.n <= 60;

INSERT IGNORE INTO sms_logs
  (id, order_no, mobile, template_type, content, status, error_message, created_at)
SELECT
  91900000 + s.n,
  IF(MOD(s.n - 1, 6) < 3, CONCAT('TEST-V101-', LPAD(s.n, 4, '0')), 'LOGIN'),
  u.mobile,
  ELT(
    MOD(s.n - 1, 6) + 1,
    'DELIVERED', 'FAILED', 'REFUNDED',
    'ADMIN_LOGIN:admin', 'USER_LOGIN:h5', 'USER_LOGIN:web'
  ),
  CONCAT('TEST-V101 短信日志 ', s.n),
  ELT(MOD(s.n - 1, 3) + 1, 'SENT', 'SKIPPED', 'FAILED'),
  IF(
    MOD(s.n - 1, 3) = 0,
    NULL,
    IF(MOD(s.n - 1, 3) = 1, 'TEST-V101 短信未启用', 'TEST-V101 simulated SMS failure')
  ),
  TIMESTAMPADD(MINUTE, -s.n * 30, @test_v101_anchor)
FROM _test_v101_seq s
JOIN users u ON u.id = 91031000 + s.n
WHERE s.n <= 36;

INSERT IGNORE INTO member_api_credentials
  (id, user_id, app_key, app_secret, status, ip_whitelist, daily_limit,
   last_used_at, created_at)
SELECT
  92000000 + s.n,
  91031000 + s.n,
  CONCAT('TEST-V101-APP-', LPAD(s.n, 2, '0')),
  CONCAT('TEST-V101-SECRET-', LPAD(s.n, 2, '0')),
  IF(s.n = 4, 'DISABLED', 'ENABLED'),
  CASE s.n WHEN 1 THEN NULL WHEN 2 THEN JSON_ARRAY() WHEN 3 THEN JSON_ARRAY('203.0.113.7') ELSE JSON_ARRAY('203.0.113.0/24') END,
  ELT(s.n, 1, 100, 1000, 100000),
  TIMESTAMPADD(HOUR, -s.n, @test_v101_anchor),
  TIMESTAMPADD(DAY, -s.n, @test_v101_anchor)
FROM _test_v101_seq s
WHERE s.n <= 4;

-- 39 of 156 orders use the API source, so create one API log for each.
INSERT IGNORE INTO open_api_logs
  (id, user_id, app_key, path, status, message, created_at)
SELECT
  92100000 + s.n,
  o.user_id,
  CONCAT('TEST-V101-APP-', LPAD(1 + MOD(s.n - 1, 4), 2, '0')),
  '/api/open/orders',
  IF(o.status = 'FAILED', 'FAILED', 'SUCCESS'),
  CONCAT('TEST-V101 API order ', o.order_no),
  TIMESTAMPADD(MINUTE, 1, o.created_at)
FROM _test_v101_seq s
JOIN orders o ON o.id = 91200000 + s.n AND o.source_platform_code = 'api'
WHERE s.n <= 156;

COMMIT;

-- Executable verification output. A clean application reports the expected
-- counts below; nonzero orphan/card counts indicate an id collision or an
-- incompatible schema and should block use of the fixture.
SELECT 'categories' AS fixture_table, COUNT(*) AS actual_count, 40 AS expected_count
FROM categories WHERE id BETWEEN 91001001 AND 91001040
UNION ALL
SELECT 'goods', COUNT(*), 180 FROM goods WHERE id BETWEEN 91100001 AND 91100180
UNION ALL
SELECT 'users', COUNT(*), 36 FROM users WHERE id BETWEEN 91031001 AND 91031036
UNION ALL
SELECT 'orders', COUNT(*), 156 FROM orders WHERE id BETWEEN 91200001 AND 91200156
UNION ALL
SELECT 'goods_channels', COUNT(*), 180 FROM goods_channels WHERE id BETWEEN 91150001 AND 91150180
UNION ALL
SELECT 'payments', COUNT(*), 144 FROM payment_records WHERE id BETWEEN 91300001 AND 91300156
UNION ALL
SELECT 'payment_callbacks', COUNT(*), 144 FROM payment_callback_logs WHERE id BETWEEN 91700001 AND 91700156
UNION ALL
SELECT 'refunds', COUNT(*), 36 FROM refund_records WHERE id BETWEEN 91400001 AND 91400156
UNION ALL
SELECT 'delivery_tasks', COUNT(*), 72 FROM delivery_tasks WHERE id BETWEEN 91500001 AND 91500156
UNION ALL
SELECT 'order_status_logs', COUNT(*), 300 FROM order_status_logs WHERE id BETWEEN 91600001 AND 91610156
UNION ALL
SELECT 'user_credentials', COUNT(*), 36 FROM user_credentials WHERE user_id BETWEEN 91031001 AND 91031036
UNION ALL
SELECT 'admin_staff', COUNT(*), 4 FROM admin_staff WHERE id BETWEEN 92210001 AND 92210004
UNION ALL
SELECT 'payment_channels', COUNT(*), 4 FROM payment_channels WHERE id BETWEEN 92220001 AND 92220004
UNION ALL
SELECT 'price_templates', COUNT(*), 3 FROM price_templates WHERE template_id LIKE 'test-v101-%'
UNION ALL
SELECT 'product_monitor_states', COUNT(*), 12 FROM product_monitor_states WHERE channel_id BETWEEN 91150001 AND 91150012
UNION ALL
SELECT 'balance_transactions', COUNT(*), 288 FROM user_balance_transactions WHERE biz_no LIKE 'TEST-V101-%' AND remark LIKE 'TEST-V101%'
UNION ALL
SELECT 'upstream_order_numbers', COUNT(*), 20 FROM orders WHERE upstream_order_no LIKE 'TEST-V101-UPSTREAM-%'
UNION ALL
SELECT 'callback_idempotency_keys', COUNT(*), 144 FROM payment_callback_logs WHERE idempotency_key LIKE 'TEST-V101-CALLBACK-IDEM-%';

WITH RECURSIVE test_v101_category_depth AS (
  SELECT id, parent_id, 1 AS depth
  FROM categories
  WHERE id IN (91001001, 91001006, 91001011, 91001016, 91001021, 91001026, 91001031, 91001036)
  UNION ALL
  SELECT c.id, c.parent_id, d.depth + 1
  FROM categories c
  JOIN test_v101_category_depth d ON c.parent_id = d.id
  WHERE c.id BETWEEN 91001001 AND 91001040
)
SELECT MAX(depth) AS max_category_depth, SUM(depth = 5) AS level_five_leaf_count
FROM test_v101_category_depth;

SELECT status, COUNT(*) AS actual_count, 12 AS expected_count
FROM orders
WHERE id BETWEEN 91200001 AND 91200156
GROUP BY status
ORDER BY FIELD(
  status, 'CREATED', 'UNPAID', 'PAYING', 'PAID', 'DELIVERING', 'PROCURING',
  'WAITING_MANUAL', 'DELIVERED', 'FAILED', 'REFUNDING', 'REFUNDED',
  'CANCELLED', 'CLOSED'
);

SELECT
  (SELECT COUNT(*)
   FROM goods g LEFT JOIN categories c ON c.id = g.category_id
   WHERE g.id BETWEEN 91100001 AND 91100180 AND c.id IS NULL) AS orphan_goods,
  (SELECT COUNT(*)
   FROM orders o LEFT JOIN users u ON u.id = o.user_id
   WHERE o.id BETWEEN 91200001 AND 91200156 AND u.id IS NULL) AS orphan_order_users,
  (SELECT COUNT(*)
   FROM orders o LEFT JOIN goods g ON g.id = o.goods_id
   WHERE o.id BETWEEN 91200001 AND 91200156 AND g.id IS NULL) AS orphan_order_goods,
  (SELECT COUNT(*)
   FROM cards
   WHERE goods_id BETWEEN 91100001 AND 91100180) AS fixture_goods_cards;

DROP TEMPORARY TABLE IF EXISTS _test_v101_order_status;
DROP TEMPORARY TABLE IF EXISTS _test_v101_seq;
