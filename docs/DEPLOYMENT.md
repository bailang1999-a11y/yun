# 喜易云部署说明

## 本地验收

```bash
npm run check
npm run browser:check
CONCURRENCY=12 ROUNDS=20 npm run load:test
```

## 生产 Docker Compose

```bash
cp .env.example .env
# 修改 .env 中的数据库密码和端口
docker compose -f docker-compose.prod.yml up -d --build
```

默认端口：

- H5 前台：`http://服务器IP:80`
- 管理后台：`http://服务器IP:8088`
- 后端仅在容器网络内暴露，由 Nginx 反向代理 `/api` 与 `/ws`

## 上线前必须替换

- `.env` 中的数据库密码
- 后台默认账号密码逻辑
- 微信/支付宝商户参数
- 短信平台密钥
- 真实上游供应商 API 地址和签名规则
- HTTPS 证书与正式域名

## 当前可用模拟凭据

- 后台：`admin / admin123`
- 会员开放 API：`demo_app_key / demo_app_secret`
- H5 示例用户：`13800000001`
