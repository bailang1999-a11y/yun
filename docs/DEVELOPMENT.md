# 本地开发说明

## 依赖

- Node.js 22+
- npm 10+
- Docker Desktop

当前工程通过 Docker 提供 MySQL、Redis 和 Maven/JDK 后端运行环境，本机不必先安装 JDK 和 Maven。

## 启动顺序

```bash
npm install
npm run dev:infra
npm run dev:h5
npm run dev:admin
```

访问地址：

- 用户端 H5: `http://localhost:5173`
- 管理后台: `http://localhost:5174`
- Liquid Glass 原型: `http://localhost:5177`
- 后端健康检查: `http://localhost:8080/api/health`

## 常用命令

```bash
npm run build:h5
npm run build:admin
npm run build:liquid
npm run build:backend
npm run check
npm run logs:backend
```

Liquid Glass 原型独立放在 `apps/liquid-next`：

- `/`: 响应式总览，桌面显示多列，移动显示抽屉
- `/web`: Web 端 Spatial Miller Columns
- `/h5`: H5 端 Liquid Drawer

如果当前目录包含中文导致 Compose 自动项目名异常，请始终使用脚本或显式加 `-p xiyiyun`。

## Phase 1 目标

首阶段只追求卡密商品 MVP 闭环：

1. 后台创建卡密商品并导入卡密。
2. 用户端浏览商品并下单。
3. 后端模拟支付成功后自动分配卡密。
4. 用户端可在订单和卡密页查看结果。
5. 后台可查看订单和库存状态。
