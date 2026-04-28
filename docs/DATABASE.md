# 数据库设计

本文档描述虚拟商品自动发货、直充、代充平台的 MySQL 8 与 Redis 数据设计。后端采用 Spring Boot，前端包含 Vue 3 管理端与用户端。

## 设计原则

- 所有业务表使用 `BIGINT UNSIGNED` 主键，推荐雪花 ID 或数据库自增二选一，全系统保持一致。
- 金额字段使用 `DECIMAL(18,4)`，库存与次数使用整数，避免浮点误差。
- 订单号、支付流水号、退款流水号、导出任务号等外部可见编号必须全局唯一。
- 所有写接口必须以唯一业务键支撑幂等，例如 `request_id`、`out_trade_no`、`out_refund_no`、`task_no`。
- 卡密明文不落库。卡密内容使用应用层信封加密后存储，数据库仅保存密文、脱敏预览、校验哈希。
- 多租户或分组可见数据必须在查询层带上权限过滤条件，禁止只依赖前端隐藏。
- 订单导出采用异步任务表，导出文件生成后写对象存储或私有文件服务，下载 URL 需短时有效。

## 命名与公共字段

建议所有业务表包含以下字段：

```sql
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
deleted_at DATETIME(3) NULL,
version INT UNSIGNED NOT NULL DEFAULT 0
```

其中 `deleted_at` 用于软删除，唯一约束如需兼容软删除，可增加 `active_flag` 或使用业务层保证。

## 状态枚举

### 用户与管理员

| 枚举 | 值 | 说明 |
| --- | --- | --- |
| `user_status` | `NORMAL` | 正常 |
| `user_status` | `DISABLED` | 禁用 |
| `user_status` | `FROZEN` | 冻结余额或交易 |
| `admin_status` | `NORMAL` | 正常 |
| `admin_status` | `DISABLED` | 禁用 |

### 商品与库存

| 枚举 | 值 | 说明 |
| --- | --- | --- |
| `goods_type` | `CARD` | 卡密自动发货 |
| `goods_type` | `DIRECT_RECHARGE` | 直充 |
| `goods_type` | `AGENT_RECHARGE` | 代充 |
| `goods_status` | `DRAFT` | 草稿 |
| `goods_status` | `ON_SALE` | 上架 |
| `goods_status` | `OFF_SALE` | 下架 |
| `card_status` | `UNSOLD` | 未售出 |
| `card_status` | `LOCKED` | 下单锁定 |
| `card_status` | `SOLD` | 已售出 |
| `card_status` | `VOID` | 作废 |

### 订单、支付、发货、退款

| 枚举 | 值 | 说明 |
| --- | --- | --- |
| `order_status` | `CREATED` | 已创建待支付 |
| `order_status` | `PAID` | 已支付待发货 |
| `order_status` | `DELIVERING` | 发货中 |
| `order_status` | `DELIVERED` | 已发货 |
| `order_status` | `COMPLETED` | 已完成 |
| `order_status` | `CLOSED` | 已关闭 |
| `order_status` | `REFUNDING` | 退款中 |
| `order_status` | `REFUNDED` | 已退款 |
| `payment_status` | `PENDING` | 待支付 |
| `payment_status` | `SUCCESS` | 支付成功 |
| `payment_status` | `FAILED` | 支付失败 |
| `payment_status` | `CLOSED` | 已关闭 |
| `refund_status` | `PROCESSING` | 处理中 |
| `refund_status` | `SUCCESS` | 退款成功 |
| `refund_status` | `FAILED` | 退款失败 |
| `delivery_status` | `PENDING` | 待执行 |
| `delivery_status` | `RUNNING` | 执行中 |
| `delivery_status` | `SUCCESS` | 成功 |
| `delivery_status` | `FAILED` | 失败 |
| `delivery_status` | `RETRYING` | 重试中 |

### 异步任务

| 枚举 | 值 | 说明 |
| --- | --- | --- |
| `task_status` | `PENDING` | 待处理 |
| `task_status` | `RUNNING` | 处理中 |
| `task_status` | `SUCCESS` | 成功 |
| `task_status` | `FAILED` | 失败 |
| `task_status` | `CANCELED` | 已取消 |

## 关键 DDL

以下 DDL 为核心结构草案，字段可按实际业务继续细化。

```sql
CREATE TABLE users (
  id BIGINT UNSIGNED PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  mobile VARCHAR(32) NULL,
  email VARCHAR(128) NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  group_id BIGINT UNSIGNED NULL,
  balance DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  frozen_balance DECIMAL(18,4) NOT NULL DEFAULT 0.0000,
  api_enabled TINYINT(1) NOT NULL DEFAULT 0,
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_users_username (username),
  UNIQUE KEY uk_users_mobile (mobile),
  KEY idx_users_group_status (group_id, status),
  KEY idx_users_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admins (
  id BIGINT UNSIGNED PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  real_name VARCHAR(64) NULL,
  role_code VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admins_username (username),
  KEY idx_admins_role_status (role_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE categories (
  id BIGINT UNSIGNED PRIMARY KEY,
  parent_id BIGINT UNSIGNED NULL,
  name VARCHAR(128) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ON_SALE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_categories_parent_name (parent_id, name),
  KEY idx_categories_parent_sort (parent_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE goods (
  id BIGINT UNSIGNED PRIMARY KEY,
  category_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(200) NOT NULL,
  goods_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  face_value DECIMAL(18,4) NULL,
  sale_price DECIMAL(18,4) NOT NULL,
  cost_price DECIMAL(18,4) NULL,
  stock_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
  stock_count INT NOT NULL DEFAULT 0,
  min_qty INT NOT NULL DEFAULT 1,
  max_qty INT NULL,
  delivery_template JSON NULL,
  sort_no INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  KEY idx_goods_category_status_sort (category_id, status, sort_no),
  KEY idx_goods_type_status (goods_type, status),
  KEY idx_goods_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cards (
  id BIGINT UNSIGNED PRIMARY KEY,
  goods_id BIGINT UNSIGNED NOT NULL,
  batch_no VARCHAR(64) NOT NULL,
  card_ciphertext VARBINARY(4096) NOT NULL,
  card_nonce VARBINARY(32) NOT NULL,
  card_key_version VARCHAR(32) NOT NULL,
  card_hash CHAR(64) NOT NULL,
  card_preview VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UNSOLD',
  locked_order_id BIGINT UNSIGNED NULL,
  sold_order_id BIGINT UNSIGNED NULL,
  sold_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_cards_goods_hash (goods_id, card_hash),
  KEY idx_cards_goods_status (goods_id, status),
  KEY idx_cards_locked_order (locked_order_id),
  KEY idx_cards_sold_order (sold_order_id),
  KEY idx_cards_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE orders (
  id BIGINT UNSIGNED PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  goods_id BIGINT UNSIGNED NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  goods_type VARCHAR(32) NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL,
  total_amount DECIMAL(18,4) NOT NULL,
  pay_amount DECIMAL(18,4) NOT NULL,
  cost_amount DECIMAL(18,4) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  recharge_account VARCHAR(255) NULL,
  buyer_remark VARCHAR(500) NULL,
  admin_remark VARCHAR(500) NULL,
  request_id VARCHAR(128) NULL,
  paid_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_orders_order_no (order_no),
  UNIQUE KEY uk_orders_user_request (user_id, request_id),
  KEY idx_orders_user_created (user_id, created_at),
  KEY idx_orders_goods_created (goods_id, created_at),
  KEY idx_orders_status_created (status, created_at),
  KEY idx_orders_delivery_status (delivery_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_records (
  id BIGINT UNSIGNED PRIMARY KEY,
  payment_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  channel VARCHAR(32) NOT NULL,
  out_trade_no VARCHAR(128) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  channel_payload JSON NULL,
  paid_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_payment_out_trade_no (channel, out_trade_no),
  KEY idx_payment_order (order_id),
  KEY idx_payment_user_created (user_id, created_at),
  KEY idx_payment_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refund_records (
  id BIGINT UNSIGNED PRIMARY KEY,
  refund_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  payment_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  out_refund_no VARCHAR(128) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  channel_payload JSON NULL,
  refunded_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_refund_no (refund_no),
  UNIQUE KEY uk_refund_out_refund_no (out_refund_no),
  KEY idx_refund_order (order_id),
  KEY idx_refund_user_created (user_id, created_at),
  KEY idx_refund_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

```sql
CREATE TABLE suppliers (
  id BIGINT UNSIGNED PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  supplier_code VARCHAR(64) NOT NULL,
  api_base_url VARCHAR(500) NULL,
  auth_config JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  priority INT NOT NULL DEFAULT 100,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_suppliers_code (supplier_code),
  KEY idx_suppliers_status_priority (status, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE goods_supplier (
  id BIGINT UNSIGNED PRIMARY KEY,
  goods_id BIGINT UNSIGNED NOT NULL,
  supplier_id BIGINT UNSIGNED NOT NULL,
  upstream_goods_code VARCHAR(128) NOT NULL,
  cost_price DECIMAL(18,4) NULL,
  stock_count INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  priority INT NOT NULL DEFAULT 100,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_goods_supplier (goods_id, supplier_id, upstream_goods_code),
  KEY idx_goods_supplier_goods_status (goods_id, status, priority),
  KEY idx_goods_supplier_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE upstream_snapshots (
  id BIGINT UNSIGNED PRIMARY KEY,
  supplier_id BIGINT UNSIGNED NOT NULL,
  upstream_goods_code VARCHAR(128) NOT NULL,
  snapshot_type VARCHAR(32) NOT NULL,
  price DECIMAL(18,4) NULL,
  stock_count INT NULL,
  raw_payload JSON NOT NULL,
  captured_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_upstream_supplier_goods_time (supplier_id, upstream_goods_code, captured_at),
  KEY idx_upstream_type_time (snapshot_type, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE group_rules (
  id BIGINT UNSIGNED PRIMARY KEY,
  group_code VARCHAR(64) NOT NULL,
  group_name VARCHAR(128) NOT NULL,
  rule_type VARCHAR(32) NOT NULL,
  goods_id BIGINT UNSIGNED NULL,
  discount_rate DECIMAL(10,4) NULL,
  fixed_price DECIMAL(18,4) NULL,
  permissions JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_group_rule (group_code, rule_type, goods_id),
  KEY idx_group_rules_goods (goods_id),
  KEY idx_group_rules_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sms_records (
  id BIGINT UNSIGNED PRIMARY KEY,
  mobile VARCHAR(32) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  code_hash CHAR(64) NULL,
  request_id VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL,
  expires_at DATETIME(3) NULL,
  sent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sms_request (request_id),
  KEY idx_sms_mobile_scene_time (mobile, scene, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE member_api_keys (
  id BIGINT UNSIGNED PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  access_key VARCHAR(64) NOT NULL,
  secret_hash CHAR(64) NOT NULL,
  scopes JSON NULL,
  ip_whitelist JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  last_used_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_member_api_access_key (access_key),
  KEY idx_member_api_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE balance_records (
  id BIGINT UNSIGNED PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  flow_no VARCHAR(64) NOT NULL,
  change_type VARCHAR(32) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  balance_before DECIMAL(18,4) NOT NULL,
  balance_after DECIMAL(18,4) NOT NULL,
  related_type VARCHAR(32) NULL,
  related_id BIGINT UNSIGNED NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_balance_flow_no (flow_no),
  KEY idx_balance_user_created (user_id, created_at),
  KEY idx_balance_related (related_type, related_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

```sql
CREATE TABLE export_tasks (
  id BIGINT UNSIGNED PRIMARY KEY,
  task_no VARCHAR(64) NOT NULL,
  creator_type VARCHAR(32) NOT NULL,
  creator_id BIGINT UNSIGNED NOT NULL,
  export_type VARCHAR(64) NOT NULL,
  params JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  total_rows INT NULL,
  file_url VARCHAR(1000) NULL,
  file_sha256 CHAR(64) NULL,
  error_message VARCHAR(1000) NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_export_task_no (task_no),
  KEY idx_export_creator_created (creator_type, creator_id, created_at),
  KEY idx_export_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE delivery_tasks (
  id BIGINT UNSIGNED PRIMARY KEY,
  task_no VARCHAR(64) NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  goods_id BIGINT UNSIGNED NOT NULL,
  supplier_id BIGINT UNSIGNED NULL,
  delivery_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  request_payload JSON NULL,
  response_payload JSON NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uk_delivery_task_no (task_no),
  UNIQUE KEY uk_delivery_order (order_id),
  KEY idx_delivery_status_retry (status, next_retry_at),
  KEY idx_delivery_supplier_status (supplier_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
  id BIGINT UNSIGNED PRIMARY KEY,
  actor_type VARCHAR(32) NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NULL,
  ip VARCHAR(64) NULL,
  user_agent VARCHAR(500) NULL,
  before_data JSON NULL,
  after_data JSON NULL,
  request_id VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_audit_actor_time (actor_type, actor_id, created_at),
  KEY idx_audit_resource_time (resource_type, resource_id, created_at),
  KEY idx_audit_action_time (action, created_at),
  KEY idx_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

## 索引与唯一约束要点

- `orders.order_no`、`payment_records.payment_no`、`refund_records.refund_no`、`export_tasks.task_no`、`delivery_tasks.task_no` 必须唯一。
- 会员 API 下单建议传 `request_id`，落库到 `orders`，用 `UNIQUE(user_id, request_id)` 保证重复请求返回同一订单。
- 支付回调用 `UNIQUE(channel, out_trade_no)` 防止重复入账；退款用 `UNIQUE(out_refund_no)` 防止重复退款。
- 卡密去重使用 `UNIQUE(goods_id, card_hash)`，`card_hash` 为规范化卡密明文的 HMAC-SHA256，不使用普通 SHA256。
- 高频列表需要组合索引：订单按用户、状态、商品、发货状态、创建时间查询；导出任务按创建人和状态查询。
- 库存扣减优先使用条件更新：`UPDATE goods SET stock_count = stock_count - ? WHERE id = ? AND stock_count >= ?`，卡密发货则使用 `cards(goods_id, status)` 批量锁定。

## 卡密加密与脱敏

- `cards.card_ciphertext` 存储 AES-256-GCM 或等价 AEAD 密文。
- `cards.card_nonce` 存储每条卡密独立随机 nonce。
- `cards.card_key_version` 标记密钥版本，便于轮换密钥。
- `cards.card_hash` 使用服务端密钥计算 HMAC，用于去重和审计，不可直接反查。
- `cards.card_preview` 仅保存脱敏内容，例如前 4 后 4，中间替换为 `****`。
- 管理端查看或导出卡密需要独立权限、审计日志和二次确认；普通订单详情只返回用户已购买卡密。

## Redis 设计

| Key | 类型 | TTL | 说明 |
| --- | --- | --- | --- |
| `login:token:{token}` | String/Hash | 会话有效期 | 用户或管理员登录态 |
| `sms:code:{scene}:{mobile}` | String | 5-10 分钟 | 短信验证码哈希 |
| `idempotent:{scope}:{requestId}` | String | 24 小时 | 接口幂等结果缓存 |
| `order:lock:{goodsId}` | String | 5-30 秒 | 商品下单与库存扣减短锁 |
| `delivery:retry:zset` | ZSet | 无 | 发货任务重试调度 |
| `export:queue` | List/Stream | 无 | 导出任务队列 |
| `rate:{apiKey}:{minute}` | String | 1-2 分钟 | 会员 API 限流 |

Redis 只做缓存、短锁和队列辅助，订单、余额、支付、退款的最终状态必须以 MySQL 事务为准。

## 数据权限过滤

- 用户端所有订单、余额、导出任务、API Key 查询必须增加 `user_id = current_user_id`。
- 管理端按角色控制菜单、按钮和数据范围；供应商相关账号只能查看授权供应商的数据。
- 商品列表需要叠加用户分组规则：可见分类、可见商品、会员价、限购规则均由后端计算。
- 导出任务必须保存创建人，查询和下载时再次校验创建人或管理权限。

## 事务边界

- 创建订单：校验商品、计算会员价、写订单、锁库存或锁卡密应在同一事务中完成。
- 支付成功回调：写支付成功、更新订单为 `PAID`、写余额或触发发货任务必须幂等。
- 自动发货：从 `delivery_tasks` 驱动，成功后更新订单为 `DELIVERED` 或 `COMPLETED`，失败后记录重试信息。
- 退款：创建退款单、调用渠道、回调确认、回补库存或余额要拆分为可重试状态机。
