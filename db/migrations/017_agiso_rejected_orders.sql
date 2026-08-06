-- Persist authenticated Agiso business rejections without entering the payment/order state machine.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agiso_rejected_orders (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  display_order_no VARCHAR(64) NOT NULL,
  protocol VARCHAR(32) NOT NULL DEFAULT 'AGISO',
  user_id BIGINT UNSIGNED NOT NULL,
  buyer_account VARCHAR(255) NULL,
  external_order_no VARCHAR(128) NOT NULL,
  product_no BIGINT NULL,
  goods_name VARCHAR(255) NULL,
  goods_type VARCHAR(32) NULL,
  quantity INT NOT NULL DEFAULT 1,
  external_max_amount DECIMAL(18,4) NULL,
  expected_amount DECIMAL(18,4) NULL,
  recharge_account VARCHAR(255) NULL,
  recharge_fields_json JSON NULL,
  callback_url VARCHAR(500) NULL,
  reject_code VARCHAR(64) NOT NULL,
  reject_reason VARCHAR(1000) NOT NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 1,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  resolved_order_no VARCHAR(64) NULL,
  rejected_at DATETIME(3) NOT NULL,
  resolved_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agiso_rejected_order (protocol, user_id, external_order_no),
  UNIQUE KEY uk_agiso_rejected_display_order (display_order_no),
  KEY idx_agiso_rejected_state_time (state, rejected_at, id),
  KEY idx_agiso_rejected_product_time (product_no, rejected_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
