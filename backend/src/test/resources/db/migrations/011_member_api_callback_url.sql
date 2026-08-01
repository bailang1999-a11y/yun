-- 会员开放 API 的可选订单状态回调地址。
-- 空值表示不接收回调；脚本可重复执行，也可叠加在已包含该列的全新库结构上。

SET NAMES utf8mb4;

SET @member_callback_url_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member_api_credentials'
          AND COLUMN_NAME = 'callback_url'
    ),
    'SELECT 1',
    'ALTER TABLE `member_api_credentials` ADD COLUMN `callback_url` VARCHAR(500) NULL AFTER `app_secret_hash`'
);
PREPARE member_callback_url_stmt FROM @member_callback_url_ddl;
EXECUTE member_callback_url_stmt;
DEALLOCATE PREPARE member_callback_url_stmt;
