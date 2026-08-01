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

CALL add_column_if_missing('users', 'deposit', '`deposit` DECIMAL(18,4) NOT NULL DEFAULT 0 AFTER `balance`') $$
CALL add_column_if_missing('users', 'real_name_type', '`real_name_type` VARCHAR(32) NOT NULL DEFAULT ''NONE'' AFTER `deposit`') $$
CALL add_column_if_missing('users', 'real_name', '`real_name` VARCHAR(128) NULL AFTER `real_name_type`') $$
CALL add_column_if_missing('users', 'subject_name', '`subject_name` VARCHAR(200) NULL AFTER `real_name`') $$
CALL add_column_if_missing('users', 'certificate_no', '`certificate_no` VARCHAR(128) NULL AFTER `subject_name`') $$
CALL add_column_if_missing('users', 'verification_status', '`verification_status` VARCHAR(32) NOT NULL DEFAULT ''UNVERIFIED'' AFTER `certificate_no`') $$

CALL add_column_if_missing('orders', 'delivery_card_ids_json', '`delivery_card_ids_json` JSON NULL AFTER `delivery_items_json`') $$
CALL add_column_if_missing('orders', 'recharge_fields_json', '`recharge_fields_json` JSON NULL AFTER `recharge_account`') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$

DELIMITER ;
