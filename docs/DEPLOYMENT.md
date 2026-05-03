# 喜易云部署说明

## 本地验收

```bash
npm run check
npm run browser:check
CONCURRENCY=12 ROUNDS=20 npm run load:test
```

更完整的上线前核对见 [LAUNCH_CHECKLIST.md](./LAUNCH_CHECKLIST.md)。

## 生产 Docker Compose

```bash
cp .env.example .env
# 修改 .env 中的数据库密码、后台账号密码哈希、支付回调密钥和端口
docker compose -f docker-compose.prod.yml up -d --build
```

已有数据库升级时，先备份 MySQL，再执行增量迁移脚本：

```bash
docker compose exec -T mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < db/migrations/002_config_persistence.sql
```

如果是全新的 Docker 数据卷，`db/init/001_schema.sql` 会自动初始化完整表结构，不需要额外执行迁移脚本。

默认端口：

- Web 前台：`http://服务器IP:80`
- 旧版 H5：`http://服务器IP:18080`
- 管理后台：`http://服务器IP:8088`
- 后端仅在容器网络内暴露，由 Nginx 反向代理 `/api` 与 `/ws`

## 上线前必须替换

- `.env` 中的数据库密码
- `.env` 中的 `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `.env` 中的 `XIYIYUN_PAYMENT_CALLBACK_SECRET`
- `.env` 中的 `XIYIYUN_CARD_ENCRYPTION_SECRET`
- 微信/支付宝商户参数
- 短信平台密钥
- 真实上游供应商 API 地址和签名规则
- HTTPS 证书与正式域名

## 后台登录配置

后台账号由 `.env` 注入：

- `XIYIYUN_ADMIN_USERNAME`
- `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `XIYIYUN_ADMIN_NICKNAME`

支付回调密钥由 `.env` 注入：

- `XIYIYUN_PAYMENT_CALLBACK_SECRET`

卡密加密密钥由 `.env` 注入：

- `XIYIYUN_CARD_ENCRYPTION_SECRET`

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

## 当前可用模拟凭据

- 会员开放 API：`demo_app_key / demo_app_secret`
- H5 示例用户：`13800000001`
