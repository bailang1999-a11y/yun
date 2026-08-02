-- 企业微信群机器人可靠通知任务

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS wecom_robot_delivery_tasks (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  order_no VARCHAR(64) NULL,
  markdown_content TEXT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wecom_robot_delivery_event (event_id),
  KEY idx_wecom_robot_delivery_due (state, next_attempt_at, lease_until),
  KEY idx_wecom_robot_delivery_order (order_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
