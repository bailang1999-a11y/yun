# 喜易云虚拟商品平台

前后端分离的虚拟商品交易平台，面向卡密自动发货、直充上游采购、代充人工处理三类业务。

## 项目结构

- `apps/h5`: 用户购买端，Vue 3 + Vite + Vant
- `apps/admin`: 运营后台，Vue 3 + Vite + Element Plus
- `backend`: Spring Boot 单体后端骨架
- `db`: MySQL 初始化脚本
- `docs`: 产品、架构、数据库、API 和路线图文档

## 本地开发

当前机器已有 Node/npm，缺少 JDK/Maven；已先完成前端和后端文件骨架。

```bash
npm install
npm run dev:h5
npm run dev:admin
```

后端需要安装 JDK 21 和 Maven，或使用 Docker 运行数据库依赖后再启动 Spring Boot。

## 飞牛 FnOS 部署

飞牛 Docker 部署说明见 [docs/FNOS_DEPLOYMENT.md](docs/FNOS_DEPLOYMENT.md)。默认端口：

- 前台 H5：`18080`
- 管理后台：`18088`
