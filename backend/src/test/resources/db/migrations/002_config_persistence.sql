SET NAMES utf8mb4;

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

CREATE TABLE IF NOT EXISTS system_settings (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value TEXT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
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

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND COLUMN_NAME = target_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN target_table VARCHAR(64),
  IN target_index VARCHAR(64),
  IN index_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND INDEX_NAME = target_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD ', index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CALL add_column_if_missing('user_groups', 'order_enabled', '`order_enabled` TINYINT(1) NOT NULL DEFAULT 1 AFTER `status`') $$
CALL add_column_if_missing('user_groups', 'real_name_required_for_order', '`real_name_required_for_order` TINYINT(1) NOT NULL DEFAULT 0 AFTER `order_enabled`') $$
CALL add_column_if_missing('user_groups', 'price_limit_enabled', '`price_limit_enabled` TINYINT(1) NOT NULL DEFAULT 1 AFTER `real_name_required_for_order`') $$
CALL add_column_if_missing('user_groups', 'price_limit_notice', '`price_limit_notice` VARCHAR(500) NULL AFTER `price_limit_enabled`') $$
CALL add_column_if_missing('orders', 'order_ip', '`order_ip` VARCHAR(64) NULL AFTER `goods_type`') $$
CALL add_column_if_missing('orders', 'order_ip_location', '`order_ip_location` VARCHAR(128) NULL AFTER `order_ip`') $$
CALL add_column_if_missing('orders', 'delivery_message', '`delivery_message` MEDIUMTEXT NULL AFTER `delivery_status`') $$
CALL add_column_if_missing('orders', 'delivery_items_json', '`delivery_items_json` JSON NULL AFTER `delivery_message`') $$
CALL add_column_if_missing('orders', 'channel_attempts_json', '`channel_attempts_json` JSON NULL AFTER `delivery_items_json`') $$
CALL add_column_if_missing('suppliers', 'platform_type', '`platform_type` VARCHAR(64) NOT NULL DEFAULT ''CUSTOM'' AFTER `name`') $$
CALL add_column_if_missing('suppliers', 'base_url', '`base_url` VARCHAR(500) NOT NULL DEFAULT '''' AFTER `platform_type`') $$
CALL add_column_if_missing('suppliers', 'app_key', '`app_key` VARCHAR(128) NULL AFTER `base_url`') $$
CALL add_column_if_missing('suppliers', 'app_secret_masked', '`app_secret_masked` VARCHAR(128) NULL AFTER `app_key`') $$
CALL add_column_if_missing('suppliers', 'user_id', '`user_id` VARCHAR(128) NULL AFTER `app_secret_masked`') $$
CALL add_column_if_missing('suppliers', 'app_id', '`app_id` VARCHAR(128) NULL AFTER `user_id`') $$
CALL add_column_if_missing('suppliers', 'api_key', '`api_key` VARCHAR(255) NULL AFTER `app_id`') $$
CALL add_column_if_missing('suppliers', 'api_key_ciphertext', '`api_key_ciphertext` VARBINARY(1024) NULL AFTER `api_key`') $$
CALL add_column_if_missing('suppliers', 'api_key_nonce', '`api_key_nonce` VARBINARY(32) NULL AFTER `api_key_ciphertext`') $$
CALL add_column_if_missing('suppliers', 'api_key_key_version', '`api_key_key_version` VARCHAR(32) NULL AFTER `api_key_nonce`') $$
CALL add_column_if_missing('suppliers', 'api_key_hash', '`api_key_hash` CHAR(64) NULL AFTER `api_key_key_version`') $$
CALL add_column_if_missing('suppliers', 'api_key_masked', '`api_key_masked` VARCHAR(128) NULL AFTER `api_key_hash`') $$
CALL add_column_if_missing('suppliers', 'callback_url', '`callback_url` VARCHAR(500) NULL AFTER `api_key_masked`') $$
CALL add_column_if_missing('suppliers', 'timeout_seconds', '`timeout_seconds` INT NOT NULL DEFAULT 30 AFTER `callback_url`') $$
CALL add_column_if_missing('suppliers', 'balance', '`balance` DECIMAL(18,4) NOT NULL DEFAULT 0 AFTER `timeout_seconds`') $$
CALL add_column_if_missing('suppliers', 'remark', '`remark` VARCHAR(500) NULL AFTER `status`') $$
CALL add_column_if_missing('suppliers', 'last_sync_at', '`last_sync_at` DATETIME(3) NULL AFTER `remark`') $$
CALL add_column_if_missing('goods_channels', 'supplier_name', '`supplier_name` VARCHAR(128) NOT NULL DEFAULT '''' AFTER `supplier_id`') $$
CALL add_column_if_missing('goods_channels', 'supplier_goods_id', '`supplier_goods_id` VARCHAR(128) NOT NULL DEFAULT '''' AFTER `supplier_name`') $$
CALL add_column_if_missing('goods_channels', 'priority', '`priority` INT NOT NULL DEFAULT 10 AFTER `supplier_goods_id`') $$
CALL add_column_if_missing('goods_channels', 'timeout_seconds', '`timeout_seconds` INT NOT NULL DEFAULT 30 AFTER `priority`') $$
CALL add_column_if_missing('goods_channels', 'created_at', '`created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER `status`') $$
CALL add_column_if_missing('goods_channels', 'updated_at', '`updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `created_at`') $$
CALL add_column_if_missing('goods_channels', 'deleted_at', '`deleted_at` DATETIME(3) NULL AFTER `updated_at`') $$
CALL add_column_if_missing('goods_channels', 'version', '`version` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `deleted_at`') $$
CALL add_index_if_missing('user_groups', 'idx_user_groups_default_status', 'KEY `idx_user_groups_default_status` (`is_default`, `status`)') $$
CALL add_index_if_missing('suppliers', 'idx_suppliers_status_type', 'KEY `idx_suppliers_status_type` (`status`, `platform_type`)') $$
CALL add_index_if_missing('orders', 'idx_orders_user_created', 'KEY `idx_orders_user_created` (`user_id`, `created_at`)') $$
CALL add_index_if_missing('orders', 'idx_orders_goods_created', 'KEY `idx_orders_goods_created` (`goods_id`, `created_at`)') $$
CALL add_index_if_missing('orders', 'idx_orders_status_created', 'KEY `idx_orders_status_created` (`status`, `created_at`)') $$
CALL add_index_if_missing('orders', 'idx_orders_delivery_status', 'KEY `idx_orders_delivery_status` (`delivery_status`, `created_at`)') $$
CALL add_index_if_missing('orders', 'idx_orders_platform_created', 'KEY `idx_orders_platform_created` (`source_platform_id`, `created_at`)') $$
CALL add_index_if_missing('goods_channels', 'idx_goods_channels_goods_priority', 'KEY `idx_goods_channels_goods_priority` (`goods_id`, `priority`, `id`)') $$
CALL add_index_if_missing('goods_channels', 'idx_goods_channels_supplier', 'KEY `idx_goods_channels_supplier` (`supplier_id`, `status`)') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
DROP PROCEDURE IF EXISTS add_index_if_missing $$

DELIMITER ;
