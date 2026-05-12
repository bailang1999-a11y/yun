# 喜易云虚拟商品平台

前后端分离的虚拟商品交易平台，面向卡密自动发货、直充上游采购、代充人工处理三类业务。

## 项目结构

- `apps/h5`: 用户购买端，Vue 3 + Vite + Vant
- `apps/admin`: 运营后台，Vue 3 + Vite + Element Plus
- `backend`: Spring Boot 单体后端骨架
- `db`: MySQL 初始化脚本和增量迁移脚本
- `docs`: 产品、架构、数据库、API 和路线图文档

## 本地开发

```bash
npm install
npm run dev:web
npm run dev:h5
npm run dev:admin
```

后端和本地数据库依赖可通过 Docker Compose 启动：

```bash
npm run dev:infra
```

本地一键验收：

```bash
npm run check
```

`smoke`、`browser:check` 等脚本默认面向本地演示环境，不应直接当作生产验收凭据。

## 上线前检查

生产发布范围为 `backend`、`apps/web`、`apps/h5` 和 `apps/admin`。`apps/liquid-next` 是本地设计原型，不随生产 Compose 发布。

复制环境变量模板后，必须替换数据库密码、后台密码哈希、支付回调密钥、卡密加密密钥和正式 HTTPS CORS 来源：

```bash
cp .env.example .env
npm run prod:preflight -- --env-file .env
npm run launch:verify -- --env-file .env
```

`prod:preflight` 会在保留占位值、开发默认密码/密钥、非 HTTPS CORS、端口非法或端口冲突时失败；通过后再执行生产 Compose。
生产 Compose 默认使用 `XIYIYUN_HTTP_BIND=127.0.0.1`，三端 HTTP 入口只给同机 HTTPS 反向代理访问，避免管理后台或明文 HTTP 直接暴露公网。

已有数据库升级时，先备份并执行 `db/migrations/002_config_persistence.sql`，再启动新后端；全新数据卷会自动执行 `db/init/001_schema.sql`，不需要跑增量迁移。

## 飞牛 FnOS 部署

飞牛 Docker 部署说明见 [docs/FNOS_DEPLOYMENT.md](docs/FNOS_DEPLOYMENT.md)。默认端口：

- Web 前台：`18080`
- 旧版 H5：`18081`
- 管理后台：`18088`
