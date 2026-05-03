# 喜易云 v0.2.0 发布说明

## 版本定位

`v0.2.0` 是上线准备版本，重点补齐 Web 前台、管理后台、后端持久化、部署配置和上线文档，适合作为生产试运行前的候选版本。

## 主要更新

- 新增 `apps/web` Web 前台，包含商品分类、商品列表、登录、订单、会员中心、API 信息等页面。
- 前台商品分类交互优化：二级分类选中态更清晰，无下级分类时隐藏空的三级分类区域。
- 前台页脚新增备案信息展示，内容由管理后台设置中心配置。
- 管理后台设置中心新增“备案与声明”：公司名称、ICP备案号、公安备案号、免责声明。
- 后端系统设置新增公开读取与管理端保存字段，并通过 `system_settings` 表持久化。
- 新增 Web 前台生产 Docker 镜像和 Nginx 反向代理配置。
- 生产 Docker Compose 新增 `web` 服务，并调整飞牛端口：Web `18080`、旧版 H5 `18081`、后台 `18088`。
- 增强生产安全校验：生产环境禁止使用默认后台密码、默认支付回调密钥、默认卡密加密密钥或占位值。
- 新增上线检查清单和部署说明更新。

## 部署入口

- Web 前台：`WEB_PORT`
- 旧版 H5：`H5_PORT`
- 管理后台：`ADMIN_PORT`

生产启动：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

已有数据库升级前先备份，再执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec -T mysql \
  mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < db/migrations/002_config_persistence.sql
```

## 上线前必填

- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `XIYIYUN_ADMIN_PASSWORD_BCRYPT`
- `XIYIYUN_PAYMENT_CALLBACK_SECRET`
- `XIYIYUN_CARD_ENCRYPTION_SECRET`

详见 [上线检查清单](./LAUNCH_CHECKLIST.md)。

## 验证记录

- `npm run typecheck`：通过，覆盖 H5、Web、Admin 三套前端构建。
- 后端 Docker 内 `mvn test`：通过，35 个测试通过。
- `docker compose -f docker-compose.prod.yml --env-file .env.feiniu.example config`：通过。
- `docker build -f apps/web/Dockerfile -t xiyiyun-web:launch-check .`：通过。
