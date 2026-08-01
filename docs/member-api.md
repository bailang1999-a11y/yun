# 会员开放 API 开发文档

> 本文档面向需要通过 API 接入本系统进行商品查询、下单取货和接收订单状态通知的第三方开发者。

---

## 一、基础信息

| 项目 | 说明 |
|---|---|
| 基础地址 | `https://你的域名` |
| 数据格式 | JSON（`Content-Type: application/json`） |
| 字符编码 | UTF-8 |
| 时间格式 | ISO 8601，如 `2026-07-29T11:50:12+08:00` |

---

## 二、认证方式

每个请求都必须携带以下请求头：

| 请求头 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `X-App-Key` | string | 是 | 分配的 appKey |
| `X-Timestamp` | string | 是 | 当前 Unix 时间戳（秒），与服务器时间差不超过 **300 秒** |
| `X-Nonce` | string | 是 | 随机字符串，每次请求必须不同（建议 UUID），用于防重放 |
| `X-Signature` | string | 是 | HMAC-SHA256 签名，详见下方规则 |
| `X-Content-SHA256` | string | POST 必填 | 请求 body 的 SHA-256 摘要，**十六进制小写**，GET 请求不需要 |

### 2.1 签名算法

**第一步：构造待签名字符串**

```
# GET 请求
payload = timestamp + "\n" + nonce + "\n" + requestPath

# POST 请求（需额外拼接 body 哈希）
contentHash = lowercase(HEX(SHA256(requestBody)))
payload     = timestamp + "\n" + nonce + "\n" + requestPath + "\n" + contentHash
```

- `requestPath` 仅为路径部分，不含域名和查询参数，例如 `/api/member/orders`
- POST 请求的 `contentHash` 即 `X-Content-SHA256` 的值，两者必须一致

**第二步：计算签名**

```
signature = lowercase(HEX(HMAC-SHA256(appSecret, payload)))
```

- 密钥和消息均以 **UTF-8** 编码
- 结果取十六进制小写字符串，填入 `X-Signature`

### 2.2 签名示例（Python）

```python
import hmac, hashlib, time, uuid

app_key    = "your_app_key"
app_secret = "your_app_secret"

def sign_get(path: str) -> dict:
    timestamp = str(int(time.time()))
    nonce     = uuid.uuid4().hex
    payload   = f"{timestamp}\n{nonce}\n{path}"
    sig = hmac.new(app_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return {
        "X-App-Key":   app_key,
        "X-Timestamp": timestamp,
        "X-Nonce":     nonce,
        "X-Signature": sig,
    }

def sign_post(path: str, body: str) -> dict:
    timestamp   = str(int(time.time()))
    nonce       = uuid.uuid4().hex
    content_hash = hashlib.sha256(body.encode()).hexdigest()
    payload     = f"{timestamp}\n{nonce}\n{path}\n{content_hash}"
    sig = hmac.new(app_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return {
        "X-App-Key":        app_key,
        "X-Timestamp":      timestamp,
        "X-Nonce":          nonce,
        "X-Signature":      sig,
        "X-Content-SHA256": content_hash,
        "Content-Type":     "application/json",
    }
```

### 2.3 签名示例（Java）

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public static String signGet(String appSecret, String timestamp, String nonce, String path) throws Exception {
    String payload = timestamp + "\n" + nonce + "\n" + path;
    return hmacSha256(appSecret, payload);
}

public static String signPost(String appSecret, String timestamp, String nonce,
                               String path, String body) throws Exception {
    String contentHash = sha256(body);
    String payload = timestamp + "\n" + nonce + "\n" + path + "\n" + contentHash;
    return hmacSha256(appSecret, payload);
}

private static String hmacSha256(String secret, String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
}

private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    );
}
```

---

## 三、统一响应格式

所有接口均返回以下结构：

```json
{
  "code":    0,
  "message": "ok",
  "data":    {}
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | `0` = 成功，`-1` = 失败 |
| `message` | string | 成功时为 `"ok"`，失败时为错误原因 |
| `data` | object/array/null | 业务数据，失败时为 `null` |

**常见错误 message：**

| message | 说明 |
|---|---|
| `invalid app key` | appKey 不存在或已禁用 |
| `ip not allowed` | 客户端 IP 不在白名单 |
| `missing signature headers` | 缺少必要请求头 |
| `timestamp expired` | 时间戳超出 ±300s |
| `invalid signature` | 签名验证失败 |
| `nonce replay` | Nonce 重复（重放攻击） |
| `daily limit exceeded` | 今日请求次数超出限额 |
| `missing or invalid content hash` | POST 请求缺少或错误的 body 哈希 |
| `content hash mismatch` | body 哈希与实际内容不符 |
| `order not found` | 订单不存在 |

---

## 四、接口列表

### 4.1 查询账户信息

查询当前凭证对应用户的余额及基本信息。

```
GET /api/member/balance
```

**请求参数：** 无

**响应示例：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1001,
    "nickname": "测试用户",
    "mobile": "138****8888",
    "balance": "98.50",
    "deposit": "0.00",
    "groupId": 2,
    "groupName": "合作商",
    "status": "ACTIVE",
    "createdAt": "2026-01-01T00:00:00+08:00",
    "lastLoginAt": "2026-07-29T10:00:00+08:00"
  }
}
```

---

### 4.2 查询商品列表

获取当前用户可购买的商品列表及价格。

```
GET /api/member/goods
```

**Query 参数：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `categoryId` | long | 否 | 按分类筛选 |
| `search` | string | 否 | 关键词搜索 |
| `platform` | string | 否 | 平台标识，默认 `h5` |

**响应示例：**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": 101,
      "categoryId": 5,
      "categoryName": "视频会员",
      "goodsName": "爱奇艺月卡",
      "subTitle": "31天会员",
      "type": "CARD",
      "price": "12.80",
      "originalPrice": "15.00",
      "stock": 99,
      "sales": 1280,
      "maxBuy": 10,
      "requireRechargeAccount": false,
      "status": "ACTIVE",
      "tags": ["热销", "立即发货"]
    }
  ]
}
```

**商品关键字段说明：**

| 字段 | 说明 |
|---|---|
| `id` | 商品ID，下单时使用 |
| `type` | 商品类型，见第五章 |
| `price` | 当前用户组对应价格 |
| `stock` | 当前库存（-1 表示不限量） |
| `requireRechargeAccount` | 是否需要填写充值账号（直充类商品） |
| `status` | `ACTIVE` 上架，`INACTIVE` 下架 |

---

### 4.3 创建订单

发起采购下单，系统自动完成发货并在响应或轮询中返回卡密。

```
POST /api/member/orders
Content-Type: application/json
```

**请求 Body：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `goodsId` | long | 是 | 商品ID，来自商品列表接口 |
| `quantity` | int | 否 | 购买数量，默认 1 |
| `rechargeAccount` | string | 条件必填 | 充值账号，`requireRechargeAccount=true` 时必填 |
| `buyerRemark` | string | 否 | 买家备注 |
| `requestId` | string | 是 | **业务幂等号**，相同 requestId 不会重复下单，建议填写己方订单号 |
| `terminal` | string | 否 | 来源标识，如 `xianyu`、`wechat`，默认 `api` |
| `rechargeFields` | object | 否 | 扩展充值字段，key-value 形式 |

**请求示例：**

```json
{
  "goodsId": 101,
  "quantity": 1,
  "rechargeAccount": "",
  "buyerRemark": "闲鱼订单号:123456",
  "requestId": "xianyu-order-123456",
  "terminal": "xianyu"
}
```

**响应示例（发货成功）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "orderNo": "XY20260729001234",
    "status": "DELIVERED",
    "deliveryItems": [
      "卡号：6225880188888888  卡密：123456"
    ],
    "deliveryMessage": "发货成功",
    "goodsName": "爱奇艺月卡",
    "quantity": 1,
    "payAmount": "12.80",
    "requestId": "xianyu-order-123456",
    "createdAt": "2026-07-29T11:50:12+08:00",
    "deliveredAt": "2026-07-29T11:50:13+08:00"
  }
}
```

**响应示例（采购中）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "orderNo": "XY20260729001235",
    "status": "PROCURING",
    "deliveryItems": [],
    "deliveryMessage": "正在向上游采购，请稍后查询",
    "requestId": "xianyu-order-123457",
    "createdAt": "2026-07-29T11:50:14+08:00",
    "deliveredAt": null
  }
}
```

> 下单后若 `status` 尚未进入结果状态，可配置订单状态回调地址接收主动通知；未配置时请通过查询接口轮询，建议间隔 **3~5 秒**，超时时间不超过 **3 分钟**。

---

### 4.4 查询订单（按订单号）

```
GET /api/member/orders/{orderNo}
```

**Path 参数：**

| 参数 | 类型 | 说明 |
|---|---|---|
| `orderNo` | string | 系统订单号，来自下单响应的 `orderNo` 字段 |

**响应示例：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "orderNo": "XY20260729001234",
    "status": "DELIVERED",
    "goodsName": "爱奇艺月卡",
    "quantity": 1,
    "payAmount": "12.80",
    "rechargeAccount": "",
    "deliveryItems": [
      "卡号：6225880188888888  卡密：123456"
    ],
    "deliveryMessage": "发货成功",
    "requestId": "xianyu-order-123456",
    "createdAt": "2026-07-29T11:50:12+08:00",
    "paidAt": "2026-07-29T11:50:12+08:00",
    "deliveredAt": "2026-07-29T11:50:13+08:00"
  }
}
```

---

### 4.5 查询订单（按请求ID）

用于幂等轮询，推荐使用此接口查询下单结果。以己方订单号（`requestId`）作为查询条件，避免丢失系统 `orderNo` 时无法查询。

```
GET /api/member/orders/by-request/{requestId}
```

**Path 参数：**

| 参数 | 类型 | 说明 |
|---|---|---|
| `requestId` | string | 下单时传入的幂等号 |

**响应结构同 4.4。**

---

### 4.6 订单状态主动通知

会员在 API 设置中填写“订单状态回调地址”后，API 来源订单进入结果状态时，系统会向该地址发送 JSON 通知。回调地址留空时不会发送通知，查单接口仍可正常使用。

当前通知的状态事件：

| `eventType` | `data.orderStatus` | 说明 |
|---|---|---|
| `order.succeeded` | `SUCCESS` | 发货成功 |
| `order.failed` | `FAILED` | 发货失败 |
| `order.cancelled` | `CANCELLED` | 订单取消 |
| `order.refunded` | `REFUNDED` | 退款完成 |
| `order.closed` | `CLOSED` | 订单关闭 |

**回调请求头：**

| 请求头 | 说明 |
|---|---|
| `X-Xiyi-App-Key` | 当前会员的 App Key |
| `X-Xiyi-Event-Id` | 通知事件编号，用于幂等去重 |
| `X-Xiyi-Timestamp` | 本次发送时的 Unix 秒级时间戳 |
| `X-Xiyi-Signature-Version` | 当前固定为 `v1` |
| `X-Xiyi-Signature` | 使用现有 App Secret 计算的 HMAC-SHA256 签名 |

**签名原文：**

```text
payload = X-Xiyi-Timestamp + "\n" + 原始请求体
signature = lowercase(HEX(HMAC-SHA256(appSecret, payload)))
```

验签时必须使用收到的原始 UTF-8 请求体，不要先解析再重新序列化 JSON。

**通知示例：**

```json
{
  "eventId": "evt_0123456789abcdef0123456789abcdef",
  "eventType": "order.succeeded",
  "timestamp": 1785427364,
  "data": {
    "orderNo": "your-order-001",
    "outTradeNo": "xiyi20260730160145900060012",
    "orderStatus": "SUCCESS",
    "failReason": "",
    "orderCost": 1,
    "productNo": "10004",
    "goodsType": "DIRECT",
    "buyNum": 1
  },
  "encryptedData": {
    "algorithm": "AES-256-GCM",
    "keyDerivation": "SHA-256(xiyiyun-member-callback:v1\\n + appSecret)",
    "nonce": "Base64 编码的 12 字节随机数",
    "ciphertext": "Base64 编码的密文和 16 字节 GCM Tag"
  }
}
```

- `orderNo` 是下单时传入的 `requestId`，`outTradeNo` 是喜易云订单号。
- `data` 是可直接读取的订单状态摘要；`failReason` 只返回固定文案。
- `encryptedData` 解密后包含完整订单信息：会员与买家账号、充值账号、全部充值字段、买家备注、支付单号与方式、全部卡号卡密、完整渠道尝试记录、上游状态、上游回调信息、上游原始响应和错误、上游订单号、订单 IP 及全部时间字段。
- 接收方返回任意 **2xx** HTTP 状态即视为成功，响应体格式不限。
- 非 2xx、超时或网络错误会自动重试；同一个 `eventId` 可能收到多次，且每次重试的随机 nonce 和密文可能不同。接收方必须按 `eventId` 幂等，不能按请求体哈希去重。
- 清空回调地址后，尚未成功的通知会停止发送。
- 直充订单 `FAILED` 后允许管理员重新采购，接收方之后可能收到同一订单的 `order.succeeded`。因此应以最新状态事件或查单结果为准，不要把一次 `order.failed` 当作永不可变的最终结论。

**完整信息解密步骤：**

1. 先使用收到的原始请求体验证 `X-Xiyi-Signature`，并校验时间戳和 `eventId` 幂等。
2. 派生 32 字节密钥：`SHA-256("xiyiyun-member-callback:v1\n" + appSecret)`。
3. Base64 解码 `nonce` 和 `ciphertext`。
4. 使用 AES-256-GCM 解密，认证附加数据（AAD）为 UTF-8 编码的 `eventId`；`ciphertext` 末尾包含 16 字节 GCM Tag。

Python 解密示例：

```python
import base64, hashlib, json
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def decrypt_callback_data(payload: dict, app_secret: str) -> dict:
    encrypted = payload["encryptedData"]
    key = hashlib.sha256(
        b"xiyiyun-member-callback:v1\n" + app_secret.encode("utf-8")
    ).digest()
    plaintext = AESGCM(key).decrypt(
        base64.b64decode(encrypted["nonce"]),
        base64.b64decode(encrypted["ciphertext"]),
        payload["eventId"].encode("utf-8"),
    )
    return json.loads(plaintext.decode("utf-8"))
```

App Secret 重置后，尚未发送成功的任务会使用新 Secret 重新加密和签名；接收端应同步更新 Secret。

---

## 五、数据字典

### 5.1 订单状态（OrderStatus）

| 值 | 说明 | 是否结果状态 |
|---|---|---|
| `CREATED` | 已创建，等待支付 | — |
| `UNPAID` | 待支付 | — |
| `PAYING` | 支付中 | — |
| `PAID` | 已支付，等待发货 | — |
| `DELIVERING` | 发货处理中 | — |
| `PROCURING` | 正在向上游采购 | — |
| `WAITING_MANUAL` | 等待人工处理 | — |
| `DELIVERED` | **发货成功** ✅ | ✅ |
| `FAILED` | **当前发货失败，可由管理员重试** ❌ | ✅ |
| `REFUNDING` | 退款中 | — |
| `REFUNDED` | 已退款 | ✅ |
| `CANCELLED` | 已取消 | ✅ |
| `CLOSED` | 已关闭 | ✅ |

> 对接时建议重点关注 `DELIVERED`（成功）和 `FAILED`（当前处理失败）。直充订单失败后可能由管理员重试，最终状态以之后的通知或查单结果为准。

### 5.2 商品类型（GoodsType）

| 值 | 说明 |
|---|---|
| `CARD` | 卡密商品，发货内容为卡号/卡密 |
| `DIRECT` | 直充商品，需提供充值账号，发货后直接到账 |
| `MANUAL` | 人工处理商品，由客服手动发货 |

---

## 六、对接流程建议

```
1. 调用 GET /api/member/goods  →  获取商品列表，记录 goodsId
2. 用户下单时，调用 POST /api/member/orders
   - 传入 goodsId、requestId（建议用己方订单号）
3. 若响应 status == DELIVERED  →  直接取 deliveryItems 里的卡密
4. 若响应 status 尚未进入结果状态（PROCURING 等）：
   - 已填写回调地址：等待订单状态主动通知
   - 未填写回调地址：轮询 GET /api/member/orders/by-request/{requestId}
   - 轮询时每次间隔 3~5 秒
   - 直到 status 变为 DELIVERED；FAILED 表示当前处理失败，直充订单仍可能被管理员重试
5. DELIVERED：取 deliveryItems 发给用户
   FAILED：提示当前处理失败，并继续接受后续状态通知或按业务需要复查
```

### IP 白名单

如果后台配置了 IP 白名单，请确保发起请求的服务器出口 IP 已加入白名单，否则会返回 `ip not allowed`。

### 每日限额

凭证有每日请求次数上限，超出后返回 `daily limit exceeded`。如需提高限额，联系管理员在后台调整。

---

## 七、完整请求示例

以 Python 为例，下单并使用轮询兜底取卡：

```python
import hmac, hashlib, time, uuid, json
import requests

BASE_URL   = "https://你的域名"
APP_KEY    = "your_app_key"
APP_SECRET = "your_app_secret"

def headers_get(path):
    ts    = str(int(time.time()))
    nonce = uuid.uuid4().hex
    payload = f"{ts}\n{nonce}\n{path}"
    sig = hmac.new(APP_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return {"X-App-Key": APP_KEY, "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig}

def headers_post(path, body_str):
    ts    = str(int(time.time()))
    nonce = uuid.uuid4().hex
    ch    = hashlib.sha256(body_str.encode()).hexdigest()
    payload = f"{ts}\n{nonce}\n{path}\n{ch}"
    sig = hmac.new(APP_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return {
        "X-App-Key": APP_KEY, "X-Timestamp": ts, "X-Nonce": nonce,
        "X-Signature": sig, "X-Content-SHA256": ch, "Content-Type": "application/json"
    }

# 下单
order_path = "/api/member/orders"
body = json.dumps({"goodsId": 101, "quantity": 1, "requestId": "my-order-001", "terminal": "xianyu"})
resp = requests.post(BASE_URL + order_path, data=body, headers=headers_post(order_path, body))
result = resp.json()

# 轮询
request_id = "my-order-001"
for _ in range(36):                          # 最多等 3 分钟
    r = requests.get(
        f"{BASE_URL}/api/member/orders/by-request/{request_id}",
        headers=headers_get(f"/api/member/orders/by-request/{request_id}")
    )
    data = r.json().get("data", {})
    status = data.get("status")
    if status == "DELIVERED":
        print("卡密：", data["deliveryItems"])
        break
    elif status == "FAILED":
        print("发货失败：", data.get("deliveryMessage"))
        break
    time.sleep(5)
```
