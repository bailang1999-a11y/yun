-- v1.31 / 阿奇索终态回调持久化任务
--
-- 回调请求先落任务、再创建订单。发送过程使用带租约的 SENDING 状态，进程重启后
-- 由 worker 重新领取过期租约，避免只存在内存中的回调在重启时丢失。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agiso_callback_tasks (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  order_no VARCHAR(64) NULL,
  callback_url VARCHAR(500) NOT NULL,
  goods_type VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agiso_callback_user_request (user_id, request_id),
  KEY idx_agiso_callback_due (state, next_attempt_at),
  KEY idx_agiso_callback_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
