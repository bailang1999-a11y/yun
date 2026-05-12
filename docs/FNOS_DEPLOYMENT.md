# 飞牛 FnOS Docker 部署

适用于飞牛 Docker / Docker Compose 应用。默认端口避开系统常用端口。生产默认只绑定本机回环地址，下面端口用于同机 HTTPS 反向代理转发；如果临时改成局域网地址做连通性检查，真实对外收款前必须恢复本机绑定并配置 HTTPS 反向代理：

- Web 前台：`127.0.0.1:18080`
- 旧版 H5：`127.0.0.1:18081`
- 管理后台：`127.0.0.1:18088`

生产 HTTPS 入口配置见 [HTTPS_REVERSE_PROXY.md](./HTTPS_REVERSE_PROXY.md)。配置完成后，把 `.env` 的 `XIYIYUN_CORS_ALLOWED_ORIGINS` 改成真实 Web、H5、Admin HTTPS 来源。生产 Compose 默认使用 `XIYIYUN_HTTP_BIND=127.0.0.1`，Web、H5、Admin 的 HTTP 端口只给同机 HTTPS 反向代理访问。飞牛界面如果需要局域网临时检查，请只在部署调试阶段开放，真实对外收款前恢复本机绑定并启用 HTTPS。

## 方式一：SSH 到飞牛后部署

```bash
mkdir -p /vol1/1000/docker/xiyiyun
cd /vol1/1000/docker/xiyiyun
git clone https://github.com/bailang1999-a11y/yun.git .
cp .env.feiniu.example .env
```

编辑 `.env`，把 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD` 改成强密码，并设置后台密码哈希和支付回调密钥：

```bash
htpasswd -nbBC 12 '' 'your_admin_password' | sed 's/^://'
```

把输出写入 `.env` 的 `XIYIYUN_ADMIN_PASSWORD_BCRYPT`。如果通过 Docker Compose 读取该文件，bcrypt 中的 `$` 需要写成 `$$`。

```bash
openssl rand -hex 32
```

把输出写入 `.env` 的 `XIYIYUN_PAYMENT_CALLBACK_SECRET`，再次执行同一命令生成一份新的随机值，写入 `XIYIYUN_CARD_ENCRYPTION_SECRET`，然后启动：

```bash
npm run prod:preflight -- --env-file .env
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env up -d --build
```

查看状态：

```bash
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env ps
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env logs -f backend
```

更新代码：

```bash
cd /vol1/1000/docker/xiyiyun
git pull
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env exec -T mysql \
  sh -lc 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < db/migrations/002_config_persistence.sql
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env up -d --build
```

迁移脚本用于已有数据库升级，必须在启动新后端前执行。全新部署时 MySQL 会读取 `db/init/001_schema.sql` 初始化完整表结构，可以跳过这一步。

停止：

```bash
docker compose -p xiyiyun -f docker-compose.prod.yml --env-file .env down
```

## 方式二：飞牛 Docker Compose 界面

如果你不用 SSH，可以在飞牛的 Docker Compose / 项目界面里新建项目：

1. 项目名称：`xiyiyun`
2. 项目路径：建议 `/vol1/1000/docker/xiyiyun`
3. 先把仓库代码放到该目录，或用飞牛终端执行上面的 `git clone`
4. Compose 文件选择 `docker-compose.prod.yml`
5. 环境变量复制 `.env.feiniu.example` 的内容，并修改数据库密码
6. 生成并填写 `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
7. 生成并填写 `XIYIYUN_PAYMENT_CALLBACK_SECRET`
8. 生成并填写 `XIYIYUN_CARD_ENCRYPTION_SECRET`
9. 填写正式 `XIYIYUN_CORS_ALLOWED_ORIGINS`，必须是 Web、H5、Admin 的真实 HTTPS 来源，不能保留 `example.com`
10. 保持 `XIYIYUN_HTTP_BIND=127.0.0.1`，由同机 HTTPS 反向代理对外暴露
11. 如果界面支持命令预检，先执行 `npm run prod:preflight -- --env-file .env`；否则至少逐项确认没有 `please_`、`change_me`、`example.com` 或 `your-domain`
12. 点击部署

## 上线提醒

当前版本可用于局域网演示和流程验收。真实对外收款前，需要替换：

- 微信 / 支付宝正式商户配置
- 短信平台密钥
- 上游供应商真实 API 与签名规则
- 后台账号、密码哈希和权限策略
- 支付回调密钥
- HTTPS 证书与正式域名
- 正式 `XIYIYUN_CORS_ALLOWED_ORIGINS`，必须是 HTTPS 域名，不能保留示例域名
