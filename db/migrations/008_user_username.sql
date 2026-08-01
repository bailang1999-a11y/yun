-- 批次9 / 用户名功能
-- 给 users 表新增可选用户名列。脚本可重复执行，也可叠加在已包含该列的全新库结构上。
--
-- 设计约束：
--   - VARCHAR(12)：最多 12 个字符，与业务校验规则（^[a-z0-9_-]{1,12}$）对齐
--   - NULL：注册时不填不报错，允许现有会员无用户名
--   - UNIQUE KEY：用户名全局唯一；MySQL 唯一索引对 NULL 值不冲突（多行 NULL 合法）

SET NAMES utf8mb4;

SET @username_column_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'username'
    ),
    'SELECT 1',
    'ALTER TABLE `users` ADD COLUMN `username` VARCHAR(12) NULL COMMENT ''用户名（可选），仅小写字母/数字/下划线/连字符，最多12字符'' AFTER `email`'
);
PREPARE username_column_stmt FROM @username_column_ddl;
EXECUTE username_column_stmt;
DEALLOCATE PREPARE username_column_stmt;

SET @username_index_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_users_username'
    ),
    'SELECT 1',
    'ALTER TABLE `users` ADD UNIQUE KEY `uk_users_username` (`username`)'
);
PREPARE username_index_stmt FROM @username_index_ddl;
EXECUTE username_index_stmt;
DEALLOCATE PREPARE username_index_stmt;
