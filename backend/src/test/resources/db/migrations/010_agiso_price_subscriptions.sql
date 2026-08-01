-- 阿奇索商品价格订阅与推送游标。
--
-- ACTIVE 记录等待检查，CHECKING 记录持有短租约；进程退出后过期租约可重新领取。
-- CANCELLED 为终态，只有新的订阅请求可以显式重新激活并刷新价格基线。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS agiso_price_subscriptions (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  platform_user_id VARCHAR(128) NOT NULL,
  supplier_account_guid VARCHAR(64) NOT NULL,
  product_no BIGINT UNSIGNED NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  last_notified_price DECIMAL(18,4) NOT NULL,
  last_price_ver BIGINT UNSIGNED NOT NULL,
  next_check_at DATETIME(3) NULL,
  lease_until DATETIME(3) NULL,
  lease_token VARCHAR(64) NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  last_notified_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agiso_price_subscription (user_id, supplier_account_guid, product_no),
  KEY idx_agiso_price_subscription_due (state, next_check_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
