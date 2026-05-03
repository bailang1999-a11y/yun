# 喜易云上线检查清单

## 1. 代码验收

上线前在本地或 CI 执行：

```bash
npm run typecheck
docker run --rm -v "$PWD/backend:/workspace" -v xiyiyun_maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test
docker compose -f docker-compose.prod.yml --env-file .env config
```

可选压测与浏览器检查：

```bash
npm run browser:check
CONCURRENCY=12 ROUNDS=20 npm run load:test
```

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

已有数据库升级前先备份，再执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec -T mysql \
  mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < db/migrations/002_config_persistence.sql
```

本次新增的 `system_settings` 表用于保存后台设置中心里的备案、公司和免责声明信息。

## 5. 上线启动

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
docker compose -f docker-compose.prod.yml --env-file .env ps
docker compose -f docker-compose.prod.yml --env-file .env logs -f backend
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
- 生产必须启用 HTTPS，并让反向代理转发 `/api` 与 `/ws`。
- 不要在生产使用开发默认后台密码、支付回调密钥或卡密加密密钥。
- 迁移前必须备份 MySQL 数据卷。
