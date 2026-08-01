-- =============================================================================
-- 006_money_integrity.sql
-- =============================================================================
--
-- 解决什么问题
--   为后续「资金一致性改造」批次预先铺好数据库结构。本文件只建结构、不迁移数据、
--   不改动业务语义，落地后现有代码行为完全不变（新列均可空或有默认值）。
--   包含 4 组结构：
--     1. user_balance_transactions —— 用户余额流水账（含幂等唯一键）
--     2. orders.upstream_order_no  —— 上游订单号 + 唯一索引（防重复采购）
--     3. payment_callback_logs.idempotency_key —— 支付回调防重放
--     4. orders/users/goods/cards 的 version 乐观锁列兜底补齐
--
-- 适用场景
--   老库升级 + 全新库。全新库跑完 001 后，第 4 组条件不成立会安全跳过；
--   第 1~3 组是 001 里尚不存在的新结构，因此在两种路径下都会实际执行。
--
-- 执行顺序
--   001_schema.sql -> 002 -> 003 -> 004 -> 005 -> [006 本文件]
--   必须在 005 之后执行：第 3 组依赖 005 建出的 payment_callback_logs 表。
--
-- 幂等性
--   复用 002_config_persistence.sql 的 add_column_if_missing / add_index_if_missing
--   模式。连续执行任意多次均不报错、不产生重复索引。
--
-- -----------------------------------------------------------------------------
-- 设计说明 1：为什么 uk_orders_upstream 只用 (upstream_order_no) 单列
-- -----------------------------------------------------------------------------
--   已核实 db/init/001_schema.sql 中 orders 表**没有**代表供应商的列：
--   orders 只有 source_platform_id / source_platform_code（销售来源平台，即抖音/
--   淘宝等下单渠道），与「向哪个上游供应商采购」是两个不同维度。
--   供应商维度实际落在另外两张表：
--     - goods_channels.supplier_id （商品的可选供货渠道）
--     - delivery_tasks.supplier_id （某笔订单最终走哪个供应商发货）
--   因此按任务要求退化为单列唯一索引 uk_orders_upstream (upstream_order_no)。
--   这在正确性上不弱于复合索引：上游订单号本身在我方系统内全局唯一即可满足
--   「同一笔上游采购不被重复记账/重复发货」的约束。若将来 orders 表补上
--   supplier_id，可在后续迁移中改为 (supplier_id, upstream_order_no)。
--   MySQL 唯一索引允许多个 NULL，故尚未采购的订单（该列为 NULL）互不冲突。
--
-- -----------------------------------------------------------------------------
-- 设计说明 2：version 列为什么用 INT UNSIGNED 而不是 INT
-- -----------------------------------------------------------------------------
--   001 中 orders/users/goods/cards 四张表的 version 均已定义为
--   `INT UNSIGNED NOT NULL DEFAULT 0`。若此处按 INT（有符号）补列，会导致
--   「全新库=int unsigned / 老库=int」的类型漂移，正是本批次要消除的问题。
--   故统一采用 INT UNSIGNED 与 001 对齐。
--   实测四张表在 001 及 002~004 升级路径下均已存在该列，下述 CALL 为兜底空操作，
--   仅对更早期的历史库生效。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 第 1 组：用户余额流水账
--   uk_balance_tx_biz (biz_type, biz_no) 是幂等保证：同一笔业务只能记一次账。
--   写入方应依赖该唯一键冲突（INSERT IGNORE / ON DUPLICATE KEY）来防重复记账，
--   而不是先 SELECT 再 INSERT。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_balance_transactions (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  direction VARCHAR(16) NOT NULL COMMENT 'DEBIT=扣减, CREDIT=增加',
  amount DECIMAL(18,4) NOT NULL COMMENT '本次变动金额，恒为正数，方向由 direction 决定',
  balance_before DECIMAL(18,4) NOT NULL COMMENT '变动前余额',
  balance_after DECIMAL(18,4) NOT NULL COMMENT '变动后余额',
  biz_type VARCHAR(32) NOT NULL COMMENT 'ORDER_PAY/ORDER_REFUND/RECHARGE/ADMIN_ADJUST',
  biz_no VARCHAR(64) NOT NULL COMMENT '业务单号：订单号/退款号/充值单号等',
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_balance_tx_biz (biz_type, biz_no),
  KEY idx_balance_tx_user (user_id, created_at)
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

-- 第 2 组：orders 上游订单号（见文件头「设计说明 1」）
CALL add_column_if_missing('orders', 'upstream_order_no', '`upstream_order_no` VARCHAR(64) NULL COMMENT ''上游供应商订单号，未采购时为 NULL'' AFTER `request_id`') $$
CALL add_index_if_missing('orders', 'uk_orders_upstream', 'UNIQUE KEY `uk_orders_upstream` (`upstream_order_no`)') $$

-- 第 3 组：支付回调幂等键（依赖 005 建出的 payment_callback_logs）
CALL add_column_if_missing('payment_callback_logs', 'idempotency_key', '`idempotency_key` VARCHAR(128) NULL COMMENT ''回调幂等键，用于防重放'' AFTER `raw_payload`') $$
CALL add_index_if_missing('payment_callback_logs', 'uk_payment_callback_idem', 'UNIQUE KEY `uk_payment_callback_idem` (`idempotency_key`)') $$

-- 第 4 组：乐观锁 version 兜底补齐（见文件头「设计说明 2」）
CALL add_column_if_missing('orders', 'version', '`version` INT UNSIGNED NOT NULL DEFAULT 0') $$
CALL add_column_if_missing('users', 'version', '`version` INT UNSIGNED NOT NULL DEFAULT 0') $$
CALL add_column_if_missing('goods', 'version', '`version` INT UNSIGNED NOT NULL DEFAULT 0') $$
CALL add_column_if_missing('cards', 'version', '`version` INT UNSIGNED NOT NULL DEFAULT 0') $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
DROP PROCEDURE IF EXISTS add_index_if_missing $$

DELIMITER ;
