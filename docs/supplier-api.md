# 对外供货接口文档

> 本文档面向需要通过 API 对接本系统进货的第三方开发者。
> 本系统支持多套主流供货协议，对接方可选择其系统已支持的协议直接接入，无需额外改造。

---

## 一、接入准备

请联系我方获取以下凭据：

| 凭据 | 说明 |
|---|---|
| **接口地址** | 我方提供的 HTTPS 域名 |
| **AppKey** | 账号标识，请求时填入 `userid` / `UserId` / `app_key` 等字段（因协议而异） |
| **AppSecret** | 签名密钥，**请妥善保管，切勿泄露** |

同时请向我方提供：

- **对接使用的协议**（见第二节，选择贵方系统已支持的一种）
- **出口 IP 地址**（用于配置 IP 白名单；未配置白名单时不限制来源 IP）

---

## 二、支持的对接协议

本系统同时支持以下协议，选择其一即可：

| 协议 | 路径前缀 | Content-Type | 时间戳单位 |
|---|---|---|---|
| **咔咔云**（推荐）| `/dockapiv3/` | JSON | 秒 |
| 卡速售 | `/api/v1/` | JSON | 毫秒 |
| 福禄 | `/api/rechargeapi/gateway` | JSON | — |
| 蜂助手 | `/fzs-stdopen-api/api/v1/` | JSON | 毫秒 |
| 鼎信橙券 | `/user/balance/get` 等 | JSON | — |
| 浙江梵尘 | `/*.do` | Form | — |
| 京兆云 | `/api/` | Form | 秒 |

---

## 三、咔咔云协议（推荐）

### 3.1 基础信息

| 项目 | 说明 |
|---|---|
| 协议 | HTTPS |
| Content-Type | `application/json` |
| 字符编码 | UTF-8 |

### 3.2 公共请求参数

每个请求的 Body 中必须包含以下公共字段：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userid` | string | 是 | 分配的 AppKey |
| `timestamp` | string | 是 | 当前 Unix 时间戳（**秒**），与服务器时差须 ≤ 300 秒 |
| `sign` | string | 是 | 签名，详见 3.3 |

### 3.3 签名算法

**步骤：**

1. 取所有请求参数，**排除 `sign` 字段**，同时排除值为 `null` 或空字符串的参数
2. 将剩余参数按 **key 字母升序**排列
3. 拼接为 `key1=value1&key2=value2&...`（value 为 JSON 对象时需序列化为 JSON 字符串）
4. 末尾直接追加 AppSecret（无分隔符）
5. 对拼接结果计算 **MD5**，取十六进制**小写**字符串

**Python 示例：**

```python
import hashlib, time

def make_sign(params: dict, app_secret: str) -> str:
    # 排除 sign 和空值
    filtered = {k: v for k, v in params.items()
                if k != 'sign' and v is not None and v != ''}
    # key 升序排列
    sorted_items = sorted(filtered.items())
    # 拼接 key=value
    query = '&'.join(f'{k}={v}' for k, v in sorted_items)
    raw = query + app_secret
    return hashlib.md5(raw.encode('utf-8')).hexdigest()

# 使用示例
app_key    = 'your_app_key'
app_secret = 'your_app_secret'
params = {
    'userid':    app_key,
    'timestamp': str(int(time.time())),
    'goodsid':   '101',
    'buynum':    '1',
    'usorderno': 'MY_ORDER_001',
}
params['sign'] = make_sign(params, app_secret)
```

### 3.4 公共响应格式

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | number | `1` = 成功，`0` = 失败 |
| `msg` | string | 结果描述 |
| `data` | object/array | 业务数据，失败时为 `{}` |

---

### 3.5 接口详情

#### 3.5.1 查询余额

```
POST /dockapiv3/user/info
```

**请求示例：**
```json
{
  "userid": "your_app_key",
  "timestamp": "1753833600",
  "sign": "e3b0c44298fc1c14..."
}
```

**响应示例：**
```json
{
  "code": 1,
  "msg": "ok",
  "data": {
    "money": 156.8000
  }
}
```

| 响应字段 | 说明 |
|---|---|
| `data.money` | 账户可用余额（元，JSON **数字**类型，4 位小数）|

---

#### 3.5.2 查询商品分类

```
POST /dockapiv3/goods/group
```

**请求示例：** 同查询余额，仅包含公共参数。

**响应示例：**
```json
{
  "code": 1,
  "msg": "ok",
  "data": [
    {"id": 1, "name": "视频会员"},
    {"id": 2, "name": "游戏充值"}
  ]
}
```

| 响应字段 | 说明 |
|---|---|
| `data[].id` | 分类 ID，查商品列表时可按此过滤 |
| `data[].name` | 分类名称 |

---

#### 3.5.3 查询商品列表

```
POST /dockapiv3/goods/all
```

**额外请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `groupid` | number | 否 | 分类 ID，不传则返回全部 |
| `goodsname` | string | 否 | 商品名称关键词 |
| `page` | number | 否 | 页码，默认 `1` |
| `limit` | number | 否 | 每页条数，默认 `100`，最大 `500` |

**请求示例：**
```json
{
  "userid": "your_app_key",
  "timestamp": "1753833600",
  "sign": "e3b0c44298fc1c14...",
  "groupid": 1,
  "page": 1,
  "limit": 100
}
```

**响应示例：**
```json
{
  "code": 1,
  "msg": "ok",
  "count": 12,
  "data": [
    {
      "id": 101,
      "goods_id": 101,
      "goodsid": 101,
      "name": "爱奇艺月卡",
      "goods_name": "爱奇艺月卡",
      "goodsname": "爱奇艺月卡",
      "type": "CARD",
      "goods_type": "CARD",
      "cate_id": 1,
      "groupid": 1,
      "category_id": 1,
      "cate_name": "视频会员",
      "category_name": "视频会员",
      "price": 18.5000,
      "goods_price": 18.5000,
      "face_value": 20.0000,
      "stock": 999,
      "stock_num": 999,
      "status": "ON_SALE",
      "can_buy": true,
      "can_no_buy": false,
      "require_recharge_account": false
    }
  ]
}
```

> 为兼容不同对接方的字段习惯，同一含义会以多个别名同时返回（如 `id` / `goods_id` / `goodsid` 值相同），取任一即可。

| 响应字段 | 说明 |
|---|---|
| `count` | 符合条件的商品总数（不受分页影响）|
| `id` / `goods_id` / `goodsid` | 商品 ID（下单时填入 `goodsid`）|
| `name` / `goods_name` / `goodsname` | 商品名称 |
| `type` / `goods_type` | 商品类型：`CARD` = 卡密，`DIRECT` = 直充 |
| `cate_id` / `groupid` / `category_id` | 所属分类 ID |
| `cate_name` / `category_name` | 分类名称 |
| `price` / `goods_price` | 进货价（元，JSON 数字）|
| `face_value` | 商品面值 / 零售价（元，JSON 数字）|
| `stock` / `stock_num` | 可售库存数量 |
| `status` | 商品状态，**字符串**；`ON_SALE` = 在售 |
| `require_recharge_account` | `true` = 下单时必须提供充值账号 |

---

#### 3.5.4 创建订单

```
POST /dockapiv3/order/create
```

**额外请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `goodsid` | number | 是 | 商品 ID |
| `buynum` | number | 否 | 购买数量，默认 `1` |
| `attach` | string | 条件必填 | 充值账号；当 `require_recharge_account=true` 时必填 |
| `usorderno` | string | 否 | **客户自定义订单号**（强烈建议填写，用于防重复下单和对账）|

**请求示例：**
```json
{
  "userid": "your_app_key",
  "timestamp": "1753833600",
  "sign": "e3b0c44298fc1c14...",
  "goodsid": 101,
  "buynum": 1,
  "attach": "",
  "usorderno": "MY_ORDER_20260730_001"
}
```

**响应示例（已发货）：**
```json
{
  "code": 1,
  "msg": "ok",
  "data": {
    "orderno": "XY2026073000001",
    "usorderno": "MY_ORDER_20260730_001",
    "status": 5,
    "money": 18.5000,
    "receipt": "发货成功",
    "cards": [
      {"card_no": "1", "card_pwd": "XXXX-XXXX-XXXX-XXXX"}
    ]
  }
}
```

**响应示例（处理中）：**
```json
{
  "code": 1,
  "msg": "ok",
  "data": {
    "orderno": "XY2026073000002",
    "usorderno": "MY_ORDER_20260730_002",
    "status": 3,
    "money": 18.5000,
    "receipt": "DELIVERING",
    "cards": []
  }
}
```

**订单状态说明（status）：**

| status | 含义 | 建议处理 |
|---|---|---|
| `3` | 处理中 | 轮询查询接口获取最终状态 |
| `5` | 已发货（成功）| 从 `cards` 取卡密，或直充已到账 |
| `4` | 失败 | 已失败/已退款/已取消，款项自动返还 |

**订单响应字段说明：**

| 字段 | 说明 |
|---|---|
| `data.orderno` | 平台订单号 |
| `data.usorderno` | 客户自定义订单号；未传时返回空字符串 |
| `data.status` | 订单状态，见上表 |
| `data.money` | 实际扣款金额（元，JSON 数字）|
| `data.receipt` | 发货备注；无备注时返回内部状态名（如 `DELIVERING`、`WAITING_MANUAL`、`REFUNDED`）|
| `data.cards` | 卡密列表；`card_no` 为序号（从 `"1"` 起），`card_pwd` 为卡密内容。未发货时为空数组 |

> **卡密类**商品在 `status=5` 时从 `cards` 取货；**直充类**商品 `cards` 为空数组，`status=5` 即表示充值成功。

---

#### 3.5.5 查询订单

```
POST /dockapiv3/order/get
```

**额外请求参数（二选一）：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `orderno` | string | 二选一 | 平台订单号 |
| `usorderno` | string | 二选一 | 客户自定义订单号 |

**请求示例：**
```json
{
  "userid": "your_app_key",
  "timestamp": "1753833600",
  "sign": "e3b0c44298fc1c14...",
  "usorderno": "MY_ORDER_20260730_001"
}
```

**响应格式：** 同创建订单接口。

---

### 3.6 错误响应

失败时返回 `code: 0`，`data` 为 `{}`，`msg` 为具体错误描述：

```json
{"code": 0, "msg": "invalid signature", "data": {}}
```

**认证类错误**

| msg | 说明 |
|---|---|
| `invalid app key` | AppKey 不存在或已被禁用 |
| `ip not allowed` | 请求来源 IP 不在白名单内（如已配置白名单）|
| `user unavailable` | 账号状态异常，请联系我方 |
| `invalid signature` | 签名校验失败。**时间戳过期也返回此错误**，请优先检查本地时间是否与标准时间同步 |
| `protocol disabled` | 该协议未对你的账号开放 |
| `outbound supply disabled` | 供货服务暂时关闭 |

**限流类错误**

| msg | 说明 |
|---|---|
| `rate limit exceeded` | 超出每分钟调用上限（默认 120 次/分钟）|
| `daily limit exceeded` | 超出每日调用上限（默认 1000 次/天，仅统计成功请求）|

**业务类错误**

| msg | 说明 |
|---|---|
| `goods not found` | 商品不存在或未上架 |
| `goods unavailable` | 商品未对你的账号开放 |
| `goods stock is insufficient` | 库存不足 |
| `余额不足，请先充值` | 账户余额不足 |
| `quantity must be greater than 0` | `buynum` 必须大于 0 |
| `order not found` | 订单不存在 |
| `order number is required` | 未提供 `orderno` 或 `usorderno` |
| `requestId already used with different order parameters` | `usorderno` 已被使用，但本次请求参数与原订单不一致 |

---

### 3.7 订单幂等

传入 `usorderno` 后，服务端按「账号 + `usorderno`」做幂等：

- **相同 `usorderno` + 完全相同的下单参数** → 直接返回原订单，不会重复扣款
- **相同 `usorderno` + 不同参数** → 返回错误 `requestId already used with different order parameters`
- **不传 `usorderno`** → 不做幂等，每次调用都会创建新订单

因此**强烈建议**每笔业务订单生成唯一的 `usorderno`。网络超时后用同一个 `usorderno` 重试是安全的。

---

## 四、卡速售协议

### 4.1 基础信息

| 项目 | 说明 |
|---|---|
| 路径前缀 | `/api/v1/` |
| Content-Type | `application/json` |
| 认证方式 | 请求头（SHA1 签名）|
| 时间戳单位 | **毫秒**（与咔咔云协议不同）|

### 4.2 请求头认证

每个请求必须包含以下请求头：

| 请求头 | 必填 | 说明 |
|---|---|---|
| `UserId` | 是 | 分配的 AppKey |
| `Timestamp` | 是 | 当前时间戳（**毫秒**），与服务器时差 ≤ 300 秒 |
| `Sign` | 是 | SHA1 签名（小写）|

### 4.3 签名算法

```
原始字符串 = Timestamp + sortedJsonBody(requestBody) + AppSecret
签名 = SHA1(原始字符串).toLowerCase()
```

**sortedJsonBody 规则：** 将请求 Body 的所有 key 按字母升序排列后序列化为 JSON 字符串。

**Python 示例：**
```python
import hashlib, json, time

def sorted_json(obj):
    if isinstance(obj, dict):
        return {k: sorted_json(v) for k, v in sorted(obj.items())}
    if isinstance(obj, list):
        return [sorted_json(i) for i in obj]
    return obj

def make_sign(timestamp_ms: str, body: dict, app_secret: str) -> str:
    sorted_body = json.dumps(sorted_json(body), separators=(',', ':'))
    raw = timestamp_ms + sorted_body + app_secret
    return hashlib.sha1(raw.encode('utf-8')).hexdigest()
```

### 4.4 接口列表

成功响应 `code` 为 `200`，失败为 `400`。

| 路径 | 说明 |
|---|---|
| `POST /api/v1/user/info` | 查询余额 |
| `POST /api/v1/goods/cate` | 查询商品分类 |
| `POST /api/v1/goods/list` | 查询商品列表 |
| `POST /api/v1/order/buy` | 创建订单 |
| `POST /api/v1/order/info` | 查询订单 |

**查询余额** → `{"code": 200, "msg": "ok", "data": {"balance": 156.80}}`

**查询商品分类** → `{"code": 200, "msg": "ok", "data": [{"id": 1, "name": "视频会员"}]}`

**查询商品列表** 请求参数：`cate_id`（可选）、`keyword`（可选）、`page`（默认 1）、`limit`（默认 100，最大 500）

响应 → `{"code": 200, "msg": "ok", "data": {"total": 12, "list": [ ...商品对象... ]}}`

商品对象字段与咔咔云协议一致，可用 `id` / `goods_id`、`name` / `goods_name`、`cate_id`、`price`、`stock` 等键名读取。

**创建订单 `/api/v1/order/buy` 请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | number | 是 | 商品 ID |
| `quantity` | number | 否 | 数量（默认 1）|
| `attach` | object | 条件必填 | JSON 对象，其中 `recharge_account` 为充值账号；其余键作为自定义充值字段透传 |
| `mark` | string | 否 | 备注 |
| `external_orderno` | string | 否 | 客户自定义订单号（幂等键，规则同 3.7）|

**查询订单 `/api/v1/order/info` 请求参数：** `ordersn`（平台单号）或 `external_orderno`（客户单号）二选一。

> 注意：查询订单的 `data` 是**数组**，创建订单的 `data` 是**对象**。

**订单对象字段：**

| 字段 | 说明 |
|---|---|
| `ordersn` | 平台订单号 |
| `external_orderno` | 客户自定义订单号 |
| `status` | `2` = 处理中，`3` = 成功，`4` = 失败 |
| `total_price` | 实际扣款金额（数字）|
| `recharge_hints` | 状态描述 / 发货备注 |
| `card_list` | 卡密列表，元素为 `{"card_no": "1", "card_password": "..."}` |

**错误响应：** `{"code": 400, "msg": "错误描述", "data": {}}`（错误文案同 3.6）

---

## 五、其他支持的协议

如对接方系统已集成以下协议，亦可直接接入：

| 协议 | 路径 | AppKey 字段 | 签名字段 | 备注 |
|---|---|---|---|---|
| 福禄 | `POST /api/rechargeapi/gateway` | `app_key` | `sign`（MD5）| 通过 `method` 字段区分操作 |
| 蜂助手 | `POST /fzs-stdopen-api/api/v1/balance` 等 | `projectCode` | `sign` | 时间戳单位：毫秒 |
| 鼎信橙券 | `POST /user/balance/get` 等 | `app_id` | `sign`（MD5）| |
| 浙江梵尘 | `POST /fcsearchbalance.do` 等 | `userid` | `sign`（MD5）| Form 提交 |
| 京兆云 | `POST /api/customer` 等 | `customer_id` | `sign`（MD5）| Form 提交，时间戳单位：秒 |

如需以上协议的详细文档，请联系我方。

---

## 六、调用限制

| 限制项 | 默认值 | 说明 |
|---|---|---|
| 每分钟请求数 | 120 次 | 按 AppKey 统计，超限返回 `rate limit exceeded` |
| 每日成功请求数 | 1000 次 | 按 AppKey 统计，超限返回 `daily limit exceeded` |
| IP 白名单 | 按需配置 | 配置后仅白名单 IP 可调用，其他 IP 返回 `ip not allowed` |

以上额度可按需调整，如需提额请联系我方。

**IP 白名单说明：** 白名单为空时不限制来源 IP。配置后按 **IP 精确匹配**校验，不支持网段 / CIDR 写法，如有多个出口 IP 请全部提供。

---

## 七、注意事项

1. **金额字段为 JSON 数字**：`money`、`price`、`face_value` 等金额字段序列化为数字（如 `18.50`）而非字符串。建议使用高精度类型（Decimal / BigDecimal）解析，避免浮点误差。
2. **时间戳精度**：咔咔云、福禄、鼎信橙券、浙江梵尘、京兆云协议使用**秒**级时间戳；卡速售、蜂助手协议使用**毫秒**级时间戳，注意区分。
3. **时间戳与签名错误合并返回**：时间戳超期与签名不匹配均返回 `invalid signature`，排查时请同时检查两者。
4. **务必传自定义订单号**：`usorderno` 是幂等键（详见 3.7）。不传则每次调用都会创建新订单，网络超时重试可能导致重复扣款。
5. **异步处理**：卡密类商品通常同步返回卡密；直充类商品创建后可能为「处理中」（`status=3`），建议间隔 3～10 秒轮询查询接口，重试不超过 10 次。
6. **签名为小写**：MD5 / SHA1 结果均为**十六进制小写**字符串。服务端比对时会做大小写归一化，但仍建议按小写提交。
7. **HTTPS**：所有接口仅支持 HTTPS 访问。
