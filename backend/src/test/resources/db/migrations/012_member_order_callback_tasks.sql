-- 通用会员订单终态回调任务
--
-- 任务与订单终态快照在同一事务内写入；worker 使用租约领取并持久化重试状态，
-- 避免进程重启或网络故障导致通知丢失。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS member_order_callback_tasks (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  order_status VARCHAR(32) NOT NULL,
  callback_url VARCHAR(500) NOT NULL,
  payload_json JSON NOT NULL,
  sensitive_ciphertext LONGBLOB NOT NULL,
  sensitive_nonce VARBINARY(12) NOT NULL,
  sensitive_key_version VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_member_order_callback_event (event_id),
  KEY idx_member_order_callback_due (state, next_attempt_at, lease_until),
  KEY idx_member_order_callback_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
