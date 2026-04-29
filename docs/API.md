# API 设计

本文档描述虚拟商品自动发货、直充、代充平台的核心 REST API。后端采用 Spring Boot，默认返回 JSON。

## 通用约定

### 路径分组

| 前缀 | 调用方 | 说明 |
| --- | --- | --- |
| `/api/h5` | 用户端 Vue 3 | 登录、商品、下单、支付、订单查询 |
| `/api/admin` | 管理端 Vue 3 | 运营管理、订单处理、导出、审计 |
| `/api/member` | 会员开放 API | 外部系统下单、查单、余额查询 |
| `/api/callback` | 支付、短信、供应商 | 第三方回调入口 |

### 响应格式

```json
{
  "code": "OK",
  "message": "success",
  "requestId": "req_202604272215000001",
  "data": {}
}
```

分页响应：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

### 认证与权限

- 用户端使用 Bearer Token 或同等会话令牌。
- 管理端使用 Bearer Token，并叠加 RBAC 权限码。
- 会员开放 API 使用 `accessKey + timestamp + nonce + signature`。
- 所有列表接口由后端做权限过滤，不能依赖前端传入的 `userId` 或供应商 ID。
- 高风险接口包括卡密导出、退款、余额调整、管理员变更，必须写 `audit_logs`。

### 幂等

- 下单、支付创建、退款、导入卡密、导出订单、余额调整等写接口必须支持 `Idempotency-Key` 请求头或业务字段 `requestId`。
- 相同调用方、相同幂等键、相同参数应返回同一结果。
- 相同幂等键但参数不同应返回 `409 IDEMPOTENCY_CONFLICT`。
- 支付、退款、供应商发货回调必须通过渠道流水号唯一约束保证重复回调无副作用。

### 错误码

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `OK` | 200 | 成功 |
| `BAD_REQUEST` | 400 | 参数错误 |
| `UNAUTHORIZED` | 401 | 未登录或签名错误 |
| `FORBIDDEN` | 403 | 无权限 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键参数冲突 |
| `INSUFFICIENT_STOCK` | 409 | 库存不足 |
| `ORDER_STATE_INVALID` | 409 | 订单状态不允许操作 |
| `RATE_LIMITED` | 429 | 请求过于频繁 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |

## 用户端 API

### 认证与账户

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/h5/auth/login` | 公开 | 账号密码登录 |
| `POST` | `/api/h5/auth/sms/send` | 公开 | 发送短信验证码 |
| `POST` | `/api/h5/auth/sms/login` | 公开 | 短信登录 |
| `POST` | `/api/h5/auth/logout` | 用户 | 退出登录 |
| `GET` | `/api/h5/users/me` | 用户 | 当前用户信息 |
| `GET` | `/api/h5/users/me/balance` | 用户 | 余额查询 |
| `GET` | `/api/h5/users/me/balance-records` | 用户 | 余额明细 |

### 商品与分类

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/h5/categories` | 公开/用户 | 分类树，登录后叠加分组可见规则 |
| `GET` | `/api/h5/goods` | 公开/用户 | 商品列表，后端返回会员价和可见状态 |
| `GET` | `/api/h5/goods/{goodsId}` | 公开/用户 | 商品详情 |
| `GET` | `/api/h5/goods/{goodsId}/stock` | 公开/用户 | 商品库存摘要 |

### 订单与支付

| 方法 | 路径 | 权限 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/h5/orders` | 用户 | 是 | 创建订单 |
| `GET` | `/api/h5/orders` | 用户 | 否 | 我的订单列表，仅返回当前用户订单 |
| `GET` | `/api/h5/orders/{orderNo}` | 用户 | 否 | 订单详情，校验订单归属 |
| `POST` | `/api/h5/orders/{orderNo}/close` | 用户 | 是 | 关闭待支付订单 |
| `POST` | `/api/h5/orders/{orderNo}/pay` | 用户 | 是 | 创建支付单 |
| `GET` | `/api/h5/orders/{orderNo}/delivery` | 用户 | 否 | 查询发货结果 |
| `POST` | `/api/h5/orders/{orderNo}/refunds` | 用户 | 是 | 申请退款 |

创建订单请求示例：

```json
{
  "goodsId": "10001",
  "quantity": 1,
  "rechargeAccount": "account@example.com",
  "buyerRemark": "尽快处理",
  "requestId": "client_order_202604270001"
}
```

## 管理端 API

### 管理员与权限

| 方法 | 路径 | 权限码 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/admin/auth/login` | 公开 | 管理员登录 |
| `POST` | `/api/admin/auth/logout` | `admin:login` | 退出 |
| `GET` | `/api/admin/auth/me` | `admin:login` | 当前管理员与权限 |
| `GET` | `/api/admin/admins` | `admin:read` | 管理员列表 |
| `POST` | `/api/admin/admins` | `admin:create` | 新增管理员 |
| `PATCH` | `/api/admin/admins/{id}` | `admin:update` | 更新管理员 |
| `PATCH` | `/api/admin/admins/{id}/status` | `admin:update` | 启用或禁用管理员 |

### 用户、分组与余额

| 方法 | 路径 | 权限码 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/admin/users` | `user:read` | 否 | 用户列表 |
| `GET` | `/api/admin/users/{id}` | `user:read` | 否 | 用户详情 |
| `PATCH` | `/api/admin/users/{id}` | `user:update` | 否 | 修改用户资料、分组 |
| `PATCH` | `/api/admin/users/{id}/status` | `user:update` | 是 | 启用、禁用、冻结 |
| `POST` | `/api/admin/users/{id}/balance-adjustments` | `balance:adjust` | 是 | 余额调整，必须审计 |
| `GET` | `/api/admin/group-rules` | `group-rule:read` | 否 | 分组规则列表 |
| `POST` | `/api/admin/group-rules` | `group-rule:create` | 是 | 创建分组规则 |
| `PATCH` | `/api/admin/group-rules/{id}` | `group-rule:update` | 否 | 更新分组规则 |
| `DELETE` | `/api/admin/group-rules/{id}` | `group-rule:delete` | 是 | 删除分组规则 |

### 商品、分类与卡密

| 方法 | 路径 | 权限码 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/admin/categories` | `category:read` | 否 | 分类列表 |
| `POST` | `/api/admin/categories` | `category:create` | 是 | 新增分类 |
| `PATCH` | `/api/admin/categories/{id}` | `category:update` | 否 | 更新分类 |
| `DELETE` | `/api/admin/categories/{id}` | `category:delete` | 是 | 删除分类 |
| `GET` | `/api/admin/goods` | `goods:read` | 否 | 商品列表 |
| `POST` | `/api/admin/goods` | `goods:create` | 是 | 新增商品 |
| `PATCH` | `/api/admin/goods/{id}` | `goods:update` | 否 | 更新商品 |
| `PATCH` | `/api/admin/goods/{id}/status` | `goods:update` | 是 | 上架或下架 |
| `POST` | `/api/admin/goods/{id}/cards/import` | `card:import` | 是 | 导入卡密，服务端加密入库 |
| `GET` | `/api/admin/goods/{id}/cards` | `card:read` | 否 | 卡密列表，仅返回脱敏预览 |
| `POST` | `/api/admin/goods/{id}/cards/export` | `card:export` | 是 | 创建卡密导出任务 |
| `PATCH` | `/api/admin/cards/{cardId}/status` | `card:update` | 是 | 作废或恢复卡密 |

卡密导入说明：

- 请求体可为文件上传或文本批量导入。
- 服务端必须去重、规范化、加密、保存脱敏预览和 HMAC。
- 返回导入总数、成功数、重复数、失败行号。

### 订单、发货、支付、退款

| 方法 | 路径 | 权限码 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/admin/orders` | `order:read` | 否 | 订单列表，支持状态、商品、用户、时间筛选 |
| `GET` | `/api/admin/orders/{orderNo}` | `order:read` | 否 | 订单详情 |
| `PATCH` | `/api/admin/orders/{orderNo}/remark` | `order:update` | 否 | 修改后台备注 |
| `POST` | `/api/admin/orders/{orderNo}/deliver` | `order:deliver` | 是 | 手动发货或补发 |
| `POST` | `/api/admin/orders/{orderNo}/close` | `order:close` | 是 | 关闭订单 |
| `POST` | `/api/admin/orders/{orderNo}/refunds` | `refund:create` | 是 | 发起退款 |
| `GET` | `/api/admin/payment-records` | `payment:read` | 否 | 支付记录 |
| `GET` | `/api/admin/refund-records` | `refund:read` | 否 | 退款记录 |
| `GET` | `/api/admin/delivery-tasks` | `delivery-task:read` | 否 | 发货任务列表 |
| `POST` | `/api/admin/delivery-tasks/{taskNo}/retry` | `delivery-task:update` | 是 | 重试发货任务 |

### 供应商与上游快照

| 方法 | 路径 | 权限码 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/admin/suppliers` | `supplier:read` | 否 | 供应商列表 |
| `POST` | `/api/admin/suppliers` | `supplier:create` | 是 | 新增供应商 |
| `PATCH` | `/api/admin/suppliers/{id}` | `supplier:update` | 否 | 更新供应商 |
| `PATCH` | `/api/admin/suppliers/{id}/status` | `supplier:update` | 是 | 启用或禁用 |
| `GET` | `/api/admin/goods/{goodsId}/suppliers` | `goods-supplier:read` | 否 | 商品供应商映射 |
| `POST` | `/api/admin/goods/{goodsId}/suppliers` | `goods-supplier:update` | 是 | 绑定供应商商品 |
| `PATCH` | `/api/admin/goods/{goodsId}/suppliers/{mappingId}` | `goods-supplier:update` | 否 | 更新成本价、优先级 |
| `GET` | `/api/admin/upstream-snapshots` | `upstream:read` | 否 | 上游库存、价格快照 |
| `POST` | `/api/admin/suppliers/{id}/sync` | `upstream:sync` | 是 | 手动同步上游商品和库存 |

### 导出任务

| 方法 | 路径 | 权限码 | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/admin/export-tasks` | `export:create` | 是 | 创建异步导出任务 |
| `GET` | `/api/admin/export-tasks` | `export:read` | 否 | 我的或有权限可见的导出任务 |
| `GET` | `/api/admin/export-tasks/{taskNo}` | `export:read` | 否 | 查询任务状态 |
| `GET` | `/api/admin/export-tasks/{taskNo}/download` | `export:download` | 否 | 获取短时下载地址 |
| `POST` | `/api/admin/export-tasks/{taskNo}/cancel` | `export:update` | 是 | 取消未开始任务 |

创建订单导出任务示例：

```json
{
  "exportType": "ORDER",
  "params": {
    "status": "DELIVERED",
    "goodsId": "10001",
    "createdAtStart": "2026-04-01T00:00:00+08:00",
    "createdAtEnd": "2026-04-27T23:59:59+08:00",
    "columns": ["orderNo", "goodsName", "quantity", "payAmount", "status", "createdAt"]
  },
  "requestId": "export_order_202604270001"
}
```

导出任务处理流程：

1. 接口只创建 `export_tasks` 记录并返回 `taskNo`。
2. 后台 Worker 按权限快照和查询条件生成文件。
3. 任务成功后写入 `file_url`、`file_sha256`、`total_rows`。
4. 下载接口再次校验任务创建人或管理权限，并返回短时有效地址。

### 审计日志

| 方法 | 路径 | 权限码 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/admin/audit-logs` | `audit:read` | 查询审计日志 |
| `GET` | `/api/admin/audit-logs/{id}` | `audit:read` | 审计日志详情 |

## 会员开放 API

### 签名规则

请求头：

```text
X-Access-Key: ak_xxx
X-Timestamp: 1777300000000
X-Nonce: nonce_32_chars
X-Signature: hex_hmac_sha256
```

签名原文建议：

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(BODY)
```

服务端校验时间偏移、nonce 重放、IP 白名单、API Key 状态和 scopes。

### 接口清单

| 方法 | 路径 | Scope | 幂等 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/member/balance` | `balance:read` | 否 | 查询余额 |
| `GET` | `/api/member/categories` | `goods:read` | 否 | 分类列表 |
| `GET` | `/api/member/goods` | `goods:read` | 否 | 商品列表，返回会员价 |
| `GET` | `/api/member/goods/{goodsId}` | `goods:read` | 否 | 商品详情 |
| `POST` | `/api/member/orders` | `order:create` | 是 | API 下单 |
| `GET` | `/api/member/orders/{orderNo}` | `order:read` | 否 | 查单 |
| `GET` | `/api/member/orders/by-request/{requestId}` | `order:read` | 否 | 按幂等请求号查单 |
| `POST` | `/api/member/orders/{orderNo}/refunds` | `refund:create` | 是 | 申请退款 |

会员 API 下单响应需包含订单号、支付金额、订单状态、发货状态。卡密类订单已发货时只返回该会员订单拥有的卡密，不允许跨订单查询。

## 回调 API

| 方法 | 路径 | 来源 | 幂等依据 | 说明 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/callback/payments/{channel}` | 支付渠道 | `channel + out_trade_no` | 支付成功、失败、关闭通知 |
| `POST` | `/api/callback/refunds/{channel}` | 支付渠道 | `out_refund_no` | 退款结果通知 |
| `POST` | `/api/callback/suppliers/{supplierCode}/delivery` | 上游供应商 | 上游订单号 | 直充或代充结果 |
| `POST` | `/api/callback/sms/{provider}` | 短信供应商 | 供应商消息 ID | 短信状态回执 |

回调处理要求：

- 先验签，再落原始 payload。
- 使用数据库唯一约束和状态机判断是否已处理。
- 重复成功回调直接返回成功响应。
- 回调只推进状态，不执行无关副作用；需要异步处理时创建任务。

## 安全要求

- 卡密明文只在导入、发货响应、授权导出时短暂出现在内存中，不写日志。
- 所有日志过滤 `password`、`secret`、`token`、`card`、`signature` 等敏感字段。
- 管理端导出卡密和订单明细需要权限码、审计日志、下载短链过期时间。
- 会员开放 API 要做限流、nonce 防重放和 IP 白名单。
- 余额调整、退款、手动发货必须记录操作前后数据。
