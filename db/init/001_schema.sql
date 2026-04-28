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

CREATE TABLE IF NOT EXISTS user_groups (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500) NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_groups_name (name),
  KEY idx_user_groups_default_status (is_default, status)
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
  goods_id BIGINT UNSIGNED NOT NULL,
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
  KEY idx_cards_goods_status (goods_id, status),
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
  quantity INT NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL,
  total_amount DECIMAL(18,4) NOT NULL,
  pay_amount DECIMAL(18,4) NOT NULL,
  cost_amount DECIMAL(18,4) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
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

INSERT IGNORE INTO categories (id, parent_id, name, sort_no, status) VALUES
  (1001, NULL, '视频会员', 10, 'ON_SALE'),
  (1002, NULL, '游戏点卡', 20, 'ON_SALE'),
  (1003, NULL, '话费充值', 30, 'ON_SALE'),
  (1101, 1001, '爱奇艺会员', 11, 'ON_SALE'),
  (1102, 1001, '腾讯视频会员', 12, 'ON_SALE'),
  (1201, 1002, 'Steam 钱包', 21, 'ON_SALE');

INSERT IGNORE INTO sales_platforms (id, platform_code, platform_name, platform_type, status, sort_no, config) VALUES
  (2001, 'h5', '移动 H5', 'SELF', 'NORMAL', 10, JSON_OBJECT('entry', 'h5')),
  (2002, 'pc', 'PC 网页', 'SELF', 'NORMAL', 20, JSON_OBJECT('entry', 'pc')),
  (2003, 'miniapp', '微信小程序', 'SELF', 'NORMAL', 30, JSON_OBJECT('entry', 'miniapp'));

INSERT IGNORE INTO user_groups (id, name, description, is_default, status) VALUES
  (7001, '默认会员', '注册后自动归入的基础用户组', 1, 'ENABLED'),
  (7002, '渠道 VIP', '仅开放 H5，屏蔽人工代充类目', 0, 'ENABLED'),
  (7003, '受限会员', '风控观察组，限制游戏直充和 PC 端购买', 0, 'ENABLED');

INSERT IGNORE INTO users (id, mobile, email, nickname, group_id, balance, status) VALUES
  (8001, '13800000001', 'alpha@example.com', 'Alpha 买家', 7001, 128.6600, 'NORMAL'),
  (8002, '13800000002', 'vip@example.com', '渠道 VIP', 7002, 888.0000, 'NORMAL'),
  (8003, '13800000003', 'risk@example.com', '受限会员', 7003, 12.3000, 'FROZEN');

INSERT IGNORE INTO group_goods_rules (id, group_id, rule_type, target_id, target_code, permission) VALUES
  (9001, 7002, 'CATEGORY', 1003, NULL, 'DENY'),
  (9002, 7002, 'PLATFORM', NULL, 'pc', 'DENY'),
  (9003, 7003, 'CATEGORY', 1002, NULL, 'DENY'),
  (9004, 7003, 'PLATFORM', NULL, 'pc', 'DENY');

INSERT IGNORE INTO goods (
  id, category_id, name, goods_type, status, face_value, sale_price, cost_price,
  stock_mode, stock_count, min_qty, max_qty, delivery_template, sort_no, description
) VALUES
  (
    3001, 1101, '爱奇艺黄金 VIP 月卡', 'CARD', 'ON_SALE', 25.0000, 19.9000, 15.0000,
    'LOCAL', 6, 1, 3,
    JSON_OBJECT('delivery_type', 'CARD', 'auto_delivery', true),
    10, '本地卡密自动发货，适合 MVP 验证购买和发货流程。'
  ),
  (
    3002, 1201, 'Steam 钱包 50 元充值卡', 'CARD', 'ON_SALE', 50.0000, 48.8000, 45.0000,
    'LOCAL', 4, 1, 2,
    JSON_OBJECT('delivery_type', 'CARD', 'auto_delivery', true),
    20, 'Steam 钱包充值卡，支付成功后自动展示卡密。'
  ),
  (
    3003, 1003, '手机话费 100 元直充', 'DIRECT_RECHARGE', 'ON_SALE', 100.0000, 99.0000, 97.0000,
    'API', 9999, 1, 1,
    JSON_OBJECT('delivery_type', 'DIRECT_RECHARGE', 'account_required', true, 'account_label', '手机号'),
    30, '直充商品示例，用于验证充值账号和发货任务流程。'
  );

INSERT IGNORE INTO goods_available_platform (id, goods_id, platform_id, status) VALUES
  (4001, 3001, 2001, 'NORMAL'),
  (4002, 3001, 2002, 'NORMAL'),
  (4003, 3002, 2001, 'NORMAL'),
  (4004, 3002, 2002, 'NORMAL'),
  (4005, 3003, 2001, 'NORMAL'),
  (4006, 3003, 2002, 'NORMAL');

INSERT IGNORE INTO goods_forbidden_platform (id, goods_id, platform_id, reason) VALUES
  (5001, 3001, 2003, '暂不支持微信小程序使用'),
  (5002, 3002, 2003, '暂不支持微信小程序使用'),
  (5003, 3003, 2003, '暂不支持微信小程序使用');

INSERT IGNORE INTO cards (
  id, goods_id, batch_no, card_ciphertext, card_nonce, card_key_version, card_hash, card_preview, status
) VALUES
  (6001, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303031'), UNHEX('000000000000000000000001'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0011', 'IQY1****0001', 'UNSOLD'),
  (6002, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303032'), UNHEX('000000000000000000000002'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0012', 'IQY1****0002', 'UNSOLD'),
  (6003, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303033'), UNHEX('000000000000000000000003'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0013', 'IQY1****0003', 'UNSOLD'),
  (6004, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303034'), UNHEX('000000000000000000000004'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0014', 'IQY1****0004', 'UNSOLD'),
  (6005, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303035'), UNHEX('000000000000000000000005'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0015', 'IQY1****0005', 'UNSOLD'),
  (6006, 3001, 'MVP-IQIYI-202604', UNHEX('6d76702d69716979692d6369706865722d30303036'), UNHEX('000000000000000000000006'), 'mvp-v1', '8a6a81ff0d6b24c234889934d20b46d91637e1cb1064c2713a6d8e81bb9e0016', 'IQY1****0006', 'UNSOLD'),
  (6101, 3002, 'MVP-STEAM-202604', UNHEX('6d76702d737465616d2d6369706865722d30303031'), UNHEX('000000000000000000001001'), 'mvp-v1', '45f14da922c2396ceea643bcbf1d6ae5353e54dc15652ce197ef145fd1fe1001', 'STM5****0001', 'UNSOLD'),
  (6102, 3002, 'MVP-STEAM-202604', UNHEX('6d76702d737465616d2d6369706865722d30303032'), UNHEX('000000000000000000001002'), 'mvp-v1', '45f14da922c2396ceea643bcbf1d6ae5353e54dc15652ce197ef145fd1fe1002', 'STM5****0002', 'UNSOLD'),
  (6103, 3002, 'MVP-STEAM-202604', UNHEX('6d76702d737465616d2d6369706865722d30303033'), UNHEX('000000000000000000001003'), 'mvp-v1', '45f14da922c2396ceea643bcbf1d6ae5353e54dc15652ce197ef145fd1fe1003', 'STM5****0003', 'UNSOLD'),
  (6104, 3002, 'MVP-STEAM-202604', UNHEX('6d76702d737465616d2d6369706865722d30303034'), UNHEX('000000000000000000001004'), 'mvp-v1', '45f14da922c2396ceea643bcbf1d6ae5353e54dc15652ce197ef145fd1fe1004', 'STM5****0004', 'UNSOLD');
