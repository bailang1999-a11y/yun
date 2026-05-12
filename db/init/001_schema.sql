SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS categories (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT UNSIGNED NULL,
  name VARCHAR(128) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ON_SALE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_categories_parent_name (parent_id, name),
  KEY idx_categories_parent_sort (parent_id, sort_no),
  KEY idx_categories_status_sort (status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sales_platforms (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  platform_code VARCHAR(64) NOT NULL,
  platform_name VARCHAR(128) NOT NULL,
  platform_type VARCHAR(32) NOT NULL DEFAULT 'SELF',
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  sort_no INT NOT NULL DEFAULT 0,
  config JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sales_platforms_code (platform_code),
  KEY idx_sales_platforms_status_sort (status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS system_settings (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value TEXT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_groups (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500) NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  order_enabled TINYINT(1) NOT NULL DEFAULT 1,
  real_name_required_for_order TINYINT(1) NOT NULL DEFAULT 0,
  price_limit_enabled TINYINT(1) NOT NULL DEFAULT 1,
  price_limit_notice VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_groups_name (name),
  KEY idx_user_groups_default_status (is_default, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS card_kinds (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  cost DECIMAL(18,4) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_card_kinds_name (name),
  KEY idx_card_kinds_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recharge_fields (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  placeholder VARCHAR(255) NULL,
  help_text VARCHAR(500) NULL,
  input_type VARCHAR(32) NOT NULL DEFAULT 'text',
  is_required TINYINT(1) NOT NULL DEFAULT 0,
  sort_no INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_recharge_fields_code (code),
  KEY idx_recharge_fields_enabled_sort (enabled, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  avatar VARCHAR(500) NULL,
  mobile VARCHAR(32) NULL,
  email VARCHAR(128) NULL,
  nickname VARCHAR(128) NOT NULL,
  group_id BIGINT UNSIGNED NOT NULL,
  balance DECIMAL(18,4) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_users_mobile (mobile),
  UNIQUE KEY uk_users_email (email),
  KEY idx_users_group_status (group_id, status),
  KEY idx_users_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS group_goods_rules (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  group_id BIGINT UNSIGNED NOT NULL,
  rule_type VARCHAR(32) NOT NULL,
  target_id BIGINT UNSIGNED NULL,
  target_code VARCHAR(64) NULL,
  permission VARCHAR(32) NOT NULL,
  target_key VARCHAR(128) GENERATED ALWAYS AS (
    CASE WHEN rule_type = 'CATEGORY' THEN CAST(target_id AS CHAR) ELSE target_code END
  ) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_group_goods_rule (group_id, rule_type, target_key),
  KEY idx_group_goods_rules_group_type (group_id, rule_type),
  KEY idx_group_goods_rules_target (rule_type, target_id, target_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS suppliers (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  platform_type VARCHAR(64) NOT NULL DEFAULT 'CUSTOM',
  base_url VARCHAR(500) NOT NULL,
  app_key VARCHAR(128) NULL,
  app_secret_masked VARCHAR(128) NULL,
  user_id VARCHAR(128) NULL,
  app_id VARCHAR(128) NULL,
  api_key VARCHAR(255) NULL,
  api_key_ciphertext VARBINARY(1024) NULL,
  api_key_nonce VARBINARY(32) NULL,
  api_key_key_version VARCHAR(32) NULL,
  api_key_hash CHAR(64) NULL,
  api_key_masked VARCHAR(128) NULL,
  callback_url VARCHAR(500) NULL,
  timeout_seconds INT NOT NULL DEFAULT 30,
  balance DECIMAL(18,4) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  remark VARCHAR(500) NULL,
  last_sync_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_suppliers_name (name),
  KEY idx_suppliers_status_type (status, platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS goods_channels (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  goods_id BIGINT UNSIGNED NOT NULL,
  supplier_id BIGINT UNSIGNED NOT NULL,
  supplier_name VARCHAR(128) NOT NULL,
  supplier_goods_id VARCHAR(128) NOT NULL,
  priority INT NOT NULL DEFAULT 10,
  timeout_seconds INT NOT NULL DEFAULT 30,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_goods_channel_supplier_goods (goods_id, supplier_id, supplier_goods_id),
  KEY idx_goods_channels_goods_priority (goods_id, priority, id),
  KEY idx_goods_channels_supplier (supplier_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS goods (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  category_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(200) NOT NULL,
  goods_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  face_value DECIMAL(18,4) NULL,
  sale_price DECIMAL(18,4) NOT NULL,
  cost_price DECIMAL(18,4) NULL,
  stock_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
  stock_count INT NOT NULL DEFAULT 0,
  min_qty INT NOT NULL DEFAULT 1,
  max_qty INT NULL,
  delivery_template JSON NULL,
  sort_no INT NOT NULL DEFAULT 0,
  description MEDIUMTEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  KEY idx_goods_category_status_sort (category_id, status, sort_no),
  KEY idx_goods_type_status (goods_type, status),
  KEY idx_goods_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS goods_available_platform (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  goods_id BIGINT UNSIGNED NOT NULL,
  platform_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_goods_available_platform (goods_id, platform_id),
  KEY idx_goods_available_platform_platform (platform_id, status),
  KEY idx_goods_available_platform_goods (goods_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS goods_forbidden_platform (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  goods_id BIGINT UNSIGNED NOT NULL,
  platform_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_goods_forbidden_platform (goods_id, platform_id),
  KEY idx_goods_forbidden_platform_platform (platform_id),
  KEY idx_goods_forbidden_platform_goods (goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cards (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  goods_id BIGINT UNSIGNED NULL,
  card_kind_id BIGINT UNSIGNED NULL,
  batch_no VARCHAR(64) NOT NULL,
  card_ciphertext VARBINARY(4096) NOT NULL,
  card_nonce VARBINARY(32) NOT NULL,
  card_key_version VARCHAR(32) NOT NULL,
  card_hash CHAR(64) NOT NULL,
  card_preview VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UNSOLD',
  locked_order_id BIGINT UNSIGNED NULL,
  sold_order_id BIGINT UNSIGNED NULL,
  sold_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_cards_goods_hash (goods_id, card_hash),
  UNIQUE KEY uk_cards_kind_hash (card_kind_id, card_hash),
  KEY idx_cards_goods_status (goods_id, status),
  KEY idx_cards_kind_status (card_kind_id, status),
  KEY idx_cards_locked_order (locked_order_id),
  KEY idx_cards_sold_order (sold_order_id),
  KEY idx_cards_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  source_platform_id BIGINT UNSIGNED NULL,
  source_platform_code VARCHAR(64) NULL,
  goods_id BIGINT UNSIGNED NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  goods_type VARCHAR(32) NOT NULL,
  order_ip VARCHAR(64) NULL,
  order_ip_location VARCHAR(128) NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL,
  total_amount DECIMAL(18,4) NOT NULL,
  pay_amount DECIMAL(18,4) NOT NULL,
  cost_amount DECIMAL(18,4) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  delivery_message MEDIUMTEXT NULL,
  delivery_items_json JSON NULL,
  channel_attempts_json JSON NULL,
  recharge_account VARCHAR(255) NULL,
  buyer_remark VARCHAR(500) NULL,
  admin_remark VARCHAR(500) NULL,
  request_id VARCHAR(128) NULL,
  paid_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_orders_order_no (order_no),
  UNIQUE KEY uk_orders_user_request (user_id, request_id),
  KEY idx_orders_user_created (user_id, created_at),
  KEY idx_orders_goods_created (goods_id, created_at),
  KEY idx_orders_status_created (status, created_at),
  KEY idx_orders_delivery_status (delivery_status, created_at),
  KEY idx_orders_platform_created (source_platform_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS order_status_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  from_status VARCHAR(32) NULL,
  to_status VARCHAR(32) NOT NULL,
  operator_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  operator_id BIGINT UNSIGNED NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_order_status_logs_order (order_id, created_at),
  KEY idx_order_status_logs_order_no (order_no, created_at),
  KEY idx_order_status_logs_status (to_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS payment_records (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  payment_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  channel VARCHAR(32) NOT NULL,
  out_trade_no VARCHAR(128) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  channel_payload JSON NULL,
  paid_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_payment_out_trade_no (channel, out_trade_no),
  KEY idx_payment_order (order_id),
  KEY idx_payment_user_created (user_id, created_at),
  KEY idx_payment_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS payment_callback_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  provider VARCHAR(64) NOT NULL,
  payment_no VARCHAR(64) NULL,
  order_no VARCHAR(64) NULL,
  callback_status VARCHAR(32) NULL,
  channel_trade_no VARCHAR(128) NULL,
  result VARCHAR(32) NOT NULL,
  message VARCHAR(1000) NULL,
  raw_payload JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_payment_callback_payment (payment_no, created_at),
  KEY idx_payment_callback_order (order_no, created_at),
  KEY idx_payment_callback_result (result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS refund_records (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  refund_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  payment_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NULL,
  out_refund_no VARCHAR(128) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  channel_payload JSON NULL,
  refunded_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_refund_no (refund_no),
  UNIQUE KEY uk_refund_out_refund_no (out_refund_no),
  KEY idx_refund_order (order_id),
  KEY idx_refund_user_created (user_id, created_at),
  KEY idx_refund_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS delivery_tasks (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  goods_id BIGINT UNSIGNED NOT NULL,
  supplier_id BIGINT UNSIGNED NULL,
  delivery_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  request_payload JSON NULL,
  response_payload JSON NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_delivery_task_no (task_no),
  UNIQUE KEY uk_delivery_order (order_id),
  KEY idx_delivery_status_retry (status, next_retry_at),
  KEY idx_delivery_supplier_status (supplier_id, status),
  KEY idx_delivery_goods_status (goods_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_operation_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  admin_id BIGINT UNSIGNED NULL,
  admin_name VARCHAR(64) NULL,
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NULL,
  ip VARCHAR(64) NULL,
  user_agent VARCHAR(500) NULL,
  before_data JSON NULL,
  after_data JSON NULL,
  request_id VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_admin_operation_admin_time (admin_id, created_at),
  KEY idx_admin_operation_resource_time (resource_type, resource_id, created_at),
  KEY idx_admin_operation_action_time (action, created_at),
  KEY idx_admin_operation_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sms_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NULL,
  mobile VARCHAR(32) NOT NULL,
  template_type VARCHAR(64) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(32) NOT NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_sms_order (order_no, created_at),
  KEY idx_sms_mobile_time (mobile, created_at),
  KEY idx_sms_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS member_api_credentials (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  app_key VARCHAR(128) NOT NULL,
  app_secret VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  ip_whitelist JSON NULL,
  daily_limit INT NOT NULL DEFAULT 1000,
  last_used_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_member_api_app_key (app_key),
  KEY idx_member_api_user (user_id),
  KEY idx_member_api_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS open_api_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NULL,
  app_key VARCHAR(128) NULL,
  path VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  message VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_open_api_user_time (user_id, created_at),
  KEY idx_open_api_app_time (app_key, created_at),
  KEY idx_open_api_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO sales_platforms (id, platform_code, platform_name, platform_type, status, sort_no, config) VALUES
  (2001, 'douyin', '抖音', 'MARKETPLACE', 'NORMAL', 10, JSON_OBJECT('entry', 'douyin')),
  (2002, 'taobao', '淘宝', 'MARKETPLACE', 'NORMAL', 20, JSON_OBJECT('entry', 'taobao')),
  (2003, 'pdd', '拼多多', 'MARKETPLACE', 'NORMAL', 30, JSON_OBJECT('entry', 'pdd')),
  (2004, 'xianyu', '咸鱼', 'MARKETPLACE', 'NORMAL', 40, JSON_OBJECT('entry', 'xianyu')),
  (2005, 'xiaohongshu', '小红书', 'MARKETPLACE', 'NORMAL', 50, JSON_OBJECT('entry', 'xiaohongshu')),
  (2006, 'private', '私域', 'SELF', 'NORMAL', 60, JSON_OBJECT('entry', 'private'));

INSERT IGNORE INTO user_groups (id, name, description, is_default, status) VALUES
  (1, '默认会员', '注册后自动归入的基础用户组', 1, 'ENABLED');
