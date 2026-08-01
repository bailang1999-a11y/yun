SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

CALL add_column_if_missing('categories', 'icon', '`icon` VARCHAR(128) NULL AFTER `name`') $$
CALL add_column_if_missing('categories', 'icon_url', '`icon_url` VARCHAR(1000) NULL AFTER `icon`') $$
CALL add_column_if_missing('categories', 'custom_icon_url', '`custom_icon_url` VARCHAR(1000) NULL AFTER `icon_url`') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$

DELIMITER ;
