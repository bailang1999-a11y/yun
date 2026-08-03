-- Persist per-channel upstream unit-price changes for order-list trend analysis.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS supplier_price_history (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  goods_id BIGINT UNSIGNED NOT NULL,
  channel_id BIGINT UNSIGNED NOT NULL,
  supplier_id BIGINT UNSIGNED NOT NULL,
  supplier_name VARCHAR(128) NOT NULL,
  supplier_goods_id VARCHAR(128) NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL,
  previous_unit_price DECIMAL(18,4) NULL,
  change_amount DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  direction VARCHAR(16) NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_supplier_price_history_channel_time (channel_id, observed_at, id),
  KEY idx_supplier_price_history_goods_time (goods_id, observed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @orders_goods_status_created_index_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'orders'
          AND INDEX_NAME = 'idx_orders_goods_status_created'
    ),
    'SELECT 1',
    'ALTER TABLE `orders` ADD INDEX `idx_orders_goods_status_created` (`goods_id`, `status`, `created_at`, `id`)'
);
PREPARE orders_goods_status_created_index_stmt FROM @orders_goods_status_created_index_ddl;
EXECUTE orders_goods_status_created_index_stmt;
DEALLOCATE PREPARE orders_goods_status_created_index_stmt;
