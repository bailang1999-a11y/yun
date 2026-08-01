-- 保存下游平台下单时传入的整单最高可接受成本，不参与喜易云订单结算。

SET NAMES utf8mb4;

SET @order_external_max_amount_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'orders'
          AND COLUMN_NAME = 'external_max_amount'
    ),
    'SELECT 1',
    'ALTER TABLE `orders` ADD COLUMN `external_max_amount` DECIMAL(18,4) NULL AFTER `pay_amount`'
);
PREPARE order_external_max_amount_stmt FROM @order_external_max_amount_ddl;
EXECUTE order_external_max_amount_stmt;
DEALLOCATE PREPARE order_external_max_amount_stmt;
