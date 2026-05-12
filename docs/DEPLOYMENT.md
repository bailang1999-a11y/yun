# 喜易云部署说明

## 本地验收

以下命令面向本地开发/演示环境，`smoke`、`load:test` 和 `browser:check` 默认使用本地地址与演示账号。`smoke` 与 `load:test` 会创建订单，脚本默认拒绝非本地地址；生产门禁请使用 `npm run prod:preflight -- --env-file .env`，并在真实域名上做只读健康检查。

```bash
npm run check
npm run schema:check
npm run browser:check
CONCURRENCY=12 ROUNDS=20 npm run load:test
```

更完整的上线前核对见 [LAUNCH_CHECKLIST.md](./LAUNCH_CHECKLIST.md)。
HTTPS 入口配置见 [HTTPS_REVERSE_PROXY.md](./HTTPS_REVERSE_PROXY.md)。

## 生产 Docker Compose

```bash
cp .env.example .env
# 修改 .env 中的数据库密码、后台账号密码哈希、支付回调密钥、CORS 域名和端口
npm run prod:preflight -- --env-file .env
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env up -d --build
```

全新 Docker 数据卷会自动执行 `db/init/001_schema.sql`，可直接启动。已有数据库升级时，不要先启动新后端；先备份 MySQL，再在旧服务停止或维护窗口内执行增量迁移脚本，确认成功后再启动新版本：

```bash
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env exec -T mysql \
  sh -lc 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < db/migrations/002_config_persistence.sql
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env up -d --build
```

如果是全新的 Docker 数据卷，`db/init/001_schema.sql` 会自动初始化完整表结构和必要基础字典，不会写入演示商品、演示用户或演示卡密，不需要额外执行迁移脚本。

默认容器 HTTP 端口：

- Web 前台：`127.0.0.1:80`
- 旧版 H5：`127.0.0.1:18080`
- 管理后台：`127.0.0.1:8088`
- 后端仅在容器网络内暴露，由 Nginx 反向代理 `/api`、`/ws` 与 `/uploads`

生产 Compose 默认使用 `XIYIYUN_HTTP_BIND=127.0.0.1`，只允许本机外层 HTTPS 反向代理访问三端 HTTP 入口，避免裸 HTTP 或管理后台直接暴露公网。

## 上线前必须替换

- `.env` 中的数据库密码
- `.env` 中的 `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `.env` 中的 `XIYIYUN_PAYMENT_CALLBACK_SECRET`
- `.env` 中的 `XIYIYUN_CARD_ENCRYPTION_SECRET`
- `.env` 中的 `XIYIYUN_CORS_ALLOWED_ORIGINS`，必须改为正式 Web、H5、管理后台域名列表，不要使用 `*`
- `.env` 中的 `XIYIYUN_HTTP_BIND`，生产保持 `127.0.0.1`
- 微信/支付宝商户参数
- 短信平台密钥
- 真实上游供应商 API 地址和签名规则
- HTTPS 证书与正式域名

生产对外访问必须使用 HTTPS 反向代理，三端容器内置 Nginx 只监听 HTTP。请按 [HTTPS_REVERSE_PROXY.md](./HTTPS_REVERSE_PROXY.md) 配置 443 入口，并将 `.env` 中 `XIYIYUN_CORS_ALLOWED_ORIGINS` 改成同一组真实 HTTPS 来源。

## 生产人工配置表

| 模块 | 必填项 | 验证动作 |
| --- | --- | --- |
| 支付通道：微信 | `app_id`、`mch_id`、`api_v3_key`、`merchant_serial_no`、`private_key`、`notify_url`、`sandbox=false` | 后台启用通道后，用小额订单确认支付单创建和回调落库 |
| 支付通道：支付宝 | `app_id`、`app_private_key`、`alipay_public_key`、`gateway_url`、`notify_url`、`sandbox=false` | 后台启用通道后，用小额订单确认支付单创建和回调落库 |
| 支付通道：自定义 | `merchant_id`、`app_id`、`api_key`、`gateway_url`、`notify_url` | 确认第三方网关下单和回调验签规则一致 |
| 短信：腾讯云 | `secret_id`、`secret_key`、`sdk_app_id`、`sign_name`、`template_id`、`region`、`template_param_json` | 后台发送测试验证码，并确认登录/注册模板都可用 |
| 短信：阿里云 | `access_key_id`、`access_key_secret`、`sign_name`、`template_code`、`region`、`template_param_json` | 后台发送测试验证码，并确认登录/注册模板都可用 |
| 短信：通用 HTTP | `url`、`method`、`content_type`、`success_keyword`、`body_template` | 用真实手机号发送测试验证码，确认成功关键字匹配 |
| 供应商 | `platformType`、`baseUrl`、`appKey/appSecret` 或对应 `userId/appId/apiKey`、`callbackUrl`、`timeoutSeconds` | 后台执行连通性测试、余额刷新、远程商品同步，再绑定供货通道 |
| 站点设置 | 站点名称、Logo、客服、公司名、ICP备案、公安备案、免责声明 | 前台页脚和设置读取正常，不显示空白或测试信息 |

## 后台登录配置

后台账号由 `.env` 注入：

- `XIYIYUN_ADMIN_USERNAME`
- `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `XIYIYUN_ADMIN_NICKNAME`

支付回调密钥由 `.env` 注入：

- `XIYIYUN_PAYMENT_CALLBACK_SECRET`

卡密加密密钥由 `.env` 注入：

- `XIYIYUN_CARD_ENCRYPTION_SECRET`

后端 CORS 白名单由 `.env` 注入：

- `XIYIYUN_CORS_ALLOWED_ORIGINS`

多个域名用英文逗号分隔，例如：

```text
XIYIYUN_CORS_ALLOWED_ORIGINS=https://shop.your-domain.com,https://h5.your-domain.com,https://admin.your-domain.com
```

生产 Compose 要求显式设置 `XIYIYUN_CORS_ALLOWED_ORIGINS`。即使后端主要通过本项目 Nginx 反向代理访问，也应填写正式 Web、H5、管理后台 HTTPS 来源域名，禁止使用 `*`、示例域名或 HTTP 地址，避免浏览器直连时被默认放开。`XIYIYUN_HTTP_BIND` 应保持 `127.0.0.1`、`localhost` 或 `::1`；如果改成 `0.0.0.0`，`prod:preflight` 会失败。

上传文件生产环境会持久化到 Docker 卷 `upload-data`，三端 Nginx 已代理 `/uploads/` 到后端。

生成密码哈希：

```bash
htpasswd -nbBC 12 '' 'your_admin_password' | sed 's/^://'
```

写入 `.env` 时，如果通过 Docker Compose 读取该文件，bcrypt 中的 `$` 需要写成 `$$`。

本地开发未配置时默认仍可使用 `admin / admin123`，生产 Compose 会要求显式设置密码哈希。

生成支付回调密钥：

```bash
openssl rand -hex 32
```

生产 Compose 会要求显式设置支付回调密钥，避免使用开发默认值。

## 首次上线数据

全新生产库不会自动创建前台会员、商品、卡密或开放 API 凭据。请在后台完成分类、商品、卡密、会员或开放 API 配置后再对外开放前台。
