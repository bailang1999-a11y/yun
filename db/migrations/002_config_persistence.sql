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
  IF NOT EXISTS (
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

CALL add_column_if_missing('user_groups', 'order_enabled', '`order_enabled` TINYINT(1) NOT NULL DEFAULT 1 AFTER `status`') $$
CALL add_column_if_missing('user_groups', 'real_name_required_for_order', '`real_name_required_for_order` TINYINT(1) NOT NULL DEFAULT 0 AFTER `order_enabled`') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$

DELIMITER ;
