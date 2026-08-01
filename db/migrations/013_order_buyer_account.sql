-- 保存下单时的买家账号，确保服务重启后的订单回调仍能提供完整订单快照。

SET NAMES utf8mb4;

SET @order_buyer_account_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'orders'
          AND COLUMN_NAME = 'buyer_account'
    ),
    'SELECT 1',
    'ALTER TABLE `orders` ADD COLUMN `buyer_account` VARCHAR(255) NULL AFTER `user_id`'
);
PREPARE order_buyer_account_stmt FROM @order_buyer_account_ddl;
EXECUTE order_buyer_account_stmt;
DEALLOCATE PREPARE order_buyer_account_stmt;
