-- =============================================================================
-- 005_cards_kind_and_callback_logs.sql
-- =============================================================================
--
-- 解决什么问题
--   db/init/001_schema.sql（全新库建表脚本）在 v1.01 期间被事后修改过，加入了
--   卡密种类（card_kinds）相关结构与支付回调日志表，但迁移链 002~004 从未补上
--   对应的增量脚本。结果是：
--     - 全新库（只跑 001）  -> 结构正确
--     - 已存在的老库（跑 002~004）-> 缺 cards.card_kind_id、缺 payment_callback_logs
--   后端能正常启动，但真实交易链路会失败：用户付款后发不出卡密。属于上线阻断问题。
--
--   本迁移把老库结构补齐到与当前 001 完全一致，具体补齐 4 处：
--     1. cards.card_kind_id  BIGINT UNSIGNED NULL（位于 goods_id 之后）
--     2. cards.goods_id      由 NOT NULL 放宽为 NULL（卡密可只归属种类、不绑商品）
--     3. cards 索引 uk_cards_kind_hash / idx_cards_kind_status
--     4. payment_callback_logs 整表（字段/索引/引擎/字符集与 001 逐字对齐）
--
-- 适用场景
--   老库升级。全新库跑完 001 后本脚本全部条件不成立，会安全跳过（无副作用）。
--
-- 执行顺序
--   001_schema.sql -> 002 -> 003 -> 004 -> [005 本文件] -> 006
--
-- 幂等性
--   复用 002_config_persistence.sql 的 add_column_if_missing / add_index_if_missing
--   模式，并额外引入 modify_column_if_not_nullable（只在列当前为 NOT NULL 时才
--   执行 MODIFY）。连续执行任意多次均不报错、不产生重复索引。
--
-- 关于第 2 步的安全性
--   NOT NULL -> NULL 是放宽约束，不会因表中已有数据而失败（无需回填、不丢数据）。
--   MODIFY COLUMN 会重写整个列定义，因此这里逐字保留 001 中的类型与属性
--   （BIGINT UNSIGNED，无默认值、无注释）；001 里该列本身也没有 COMMENT/DEFAULT。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 步骤 4：payment_callback_logs 整表补齐
-- 定义与 db/init/001_schema.sql 逐字对齐（含列顺序、索引名、引擎、字符集、排序规则）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_callback_logs (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  provider VARCHAR(64) NOT NULL,
  payment_no VARCHAR(64) NULL,
  order_no VARCHAR(64) NULL,
  callback_status VARCHAR(32) NULL,
  channel_trade_no VARCHAR(128) NULL,
  result VARCHAR(32) NOT NULL,
  message VARCHAR(1000) NULL,
  raw_payload JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_payment_callback_payment (payment_no, created_at),
  KEY idx_payment_callback_order (order_no, created_at),
  KEY idx_payment_callback_result (result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND COLUMN_NAME = target_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(
  IN target_table VARCHAR(64),
  IN target_index VARCHAR(64),
  IN index_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND INDEX_NAME = target_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD ', index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

-- 仅当列存在且当前为 NOT NULL 时才执行 MODIFY，避免重复执行时反复重写表
DROP PROCEDURE IF EXISTS modify_column_if_not_nullable $$
CREATE PROCEDURE modify_column_if_not_nullable(
  IN target_table VARCHAR(64),
  IN target_column VARCHAR(64),
  IN column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = target_table
      AND COLUMN_NAME = target_column
      AND IS_NULLABLE = 'NO'
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', target_table, '` MODIFY COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

-- 步骤 1：补 cards.card_kind_id（严格对齐 001：BIGINT UNSIGNED NULL，紧随 goods_id）
CALL add_column_if_missing('cards', 'card_kind_id', '`card_kind_id` BIGINT UNSIGNED NULL AFTER `goods_id`') $$

-- 步骤 2：cards.goods_id 放宽为 NULL（保留 001 中的原类型，无默认值/注释）
CALL modify_column_if_not_nullable('cards', 'goods_id', '`goods_id` BIGINT UNSIGNED NULL') $$

-- 步骤 3：补 cards 的两个 card_kind_id 相关索引（索引列与 001 完全一致）
CALL add_index_if_missing('cards', 'uk_cards_kind_hash', 'UNIQUE KEY `uk_cards_kind_hash` (`card_kind_id`, `card_hash`)') $$
CALL add_index_if_missing('cards', 'idx_cards_kind_status', 'KEY `idx_cards_kind_status` (`card_kind_id`, `status`)') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
DROP PROCEDURE IF EXISTS add_index_if_missing $$
DROP PROCEDURE IF EXISTS modify_column_if_not_nullable $$

DELIMITER ;
