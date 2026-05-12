# 喜易云上线检查清单

## 1. 代码验收

生产发布范围仅包括 `backend`、`apps/web`、`apps/h5` 和 `apps/admin`。`apps/liquid-next` 是本地设计原型，不随 `docker-compose.prod.yml` 发布。

上线前在本地或 CI 执行：

```bash
npm run launch:verify -- --env-file .env
```

该命令只运行非破坏性检查：schema、三端构建、后端测试、生产依赖安全扫描、生产 `.env` 预检和 Compose 配置解析。它不会执行会登录、下单或压测的 `smoke`、`browser:check`、`load:test`。

也可以逐项执行：

```bash
npm run typecheck
npm run schema:check
npm run test:backend
npm run prod:preflight -- --env-file .env
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env config
```

可选压测与浏览器检查：

```bash
npm run browser:check
CONCURRENCY=12 ROUNDS=20 npm run load:test
```

`browser:check`、`load:test` 和 `smoke` 默认面向本地演示环境。生产环境不要直接使用默认演示账号或 `demo_app_key`，应显式传入真实测试地址和测试凭据，或只做只读健康检查。`smoke` 和 `load:test` 会登录并创建订单，脚本默认只允许 `localhost` / `127.0.0.1`；如需打隔离预发环境，必须显式设置 `ALLOW_MUTATING_REMOTE_TEST=1`，不要对真实生产库执行。

## 2. 生产环境变量

从样例复制：

```bash
cp .env.example .env
```

必须替换：

- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `XIYIYUN_PAYMENT_CALLBACK_SECRET`
- `XIYIYUN_CARD_ENCRYPTION_SECRET`
- `XIYIYUN_CORS_ALLOWED_ORIGINS`，必须改为正式 Web、H5、管理后台域名列表，不要使用 `*`
- `XIYIYUN_HTTP_BIND`，生产应保持 `127.0.0.1`，由外层 HTTPS 反向代理对外暴露

可按服务器网络情况调整：

- `NPM_REGISTRY`，默认 `https://registry.npmmirror.com`
- `MAVEN_MIRROR_URL`，默认 `https://maven.aliyun.com/repository/public`

如果服务器访问官方 npm 或 Maven Central 足够稳定，也可以改回官方源。

填写完成后先执行：

```bash
npm run prod:preflight -- --env-file .env
```

该命令只检查 `.env`，不会启动服务；如果仍保留占位域名、示例密钥、开发默认后台密码或非 HTTPS CORS，会直接失败。

生成后台密码哈希：

```bash
htpasswd -nbBC 12 '' 'your_admin_password' | sed 's/^://'
```

写入 Docker Compose `.env` 时，bcrypt 中的 `$` 要写成 `$$`。

生成密钥：

```bash
openssl rand -hex 32
```

## 3. 端口

默认生产端口：

- Web 前台：`WEB_PORT=80`
- 旧版 H5：`H5_PORT=18080`
- 管理后台：`ADMIN_PORT=8088`

飞牛建议端口：

- Web 前台：`WEB_PORT=18080`
- 旧版 H5：`H5_PORT=18081`
- 管理后台：`ADMIN_PORT=18088`

## 4. 数据库

全新数据卷会自动执行：

```text
db/init/001_schema.sql
```

初始化脚本只保留必要基础字典，不应包含演示商品、演示用户、演示订单或演示卡密。

已有数据库升级前先备份，再执行：

```bash
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env exec -T mysql \
  sh -lc 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < db/migrations/002_config_persistence.sql
```

已有数据库必须在新后端启动前完成迁移，否则后端会因为缺少新增配置列或配置表而启动失败。全新数据卷不需要执行增量迁移。

本次新增的 `system_settings` 表用于保存后台设置中心里的备案、公司和免责声明信息。

## 5. 上线启动

```bash
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env up -d --build
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env ps
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env logs -f backend
```

## 6. 上线后后台确认

进入管理后台，检查：

- 设置中心：站点名称、Logo、备案信息、免责声明
- 分类管理：一级、二级、三级分类是否正确展示
- 商品管理：商品状态、库存、渠道、充值字段
- 用户组：下单权限、实名要求、可见分类和平台规则
- 订单：创建、支付、发货、退款链路

## 7. 风险点

- 真实支付、短信、上游供应商参数仍需按生产商户信息接入。
- 生产必须启用 HTTPS，并让反向代理转发 `/api`、`/ws` 与 `/uploads`。
- 生产 Compose 默认只把 Web、H5、Admin 的 HTTP 端口绑定到本机回环地址，避免管理后台或明文 HTTP 直接暴露公网。
- 外层 HTTPS 反代模板见 [HTTPS_REVERSE_PROXY.md](./HTTPS_REVERSE_PROXY.md)，后台域名建议只允许办公固定 IP 或 VPN 访问。
- 生产上传文件依赖 Docker 卷 `upload-data`，迁移服务器时需要一起备份和恢复。
- 不要在生产使用开发默认后台密码、支付回调密钥或卡密加密密钥。
- 迁移前必须备份 MySQL 数据卷。
