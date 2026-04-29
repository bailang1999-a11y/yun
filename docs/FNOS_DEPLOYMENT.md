# 飞牛 FnOS Docker 部署

适用于飞牛 Docker / Docker Compose 应用。默认端口避开系统常用端口：

- 前台 H5：`http://飞牛IP:18080`
- 管理后台：`http://飞牛IP:18088`

## 方式一：SSH 到飞牛后部署

```bash
mkdir -p /vol1/1000/docker/xiyiyun
cd /vol1/1000/docker/xiyiyun
git clone https://github.com/bailang1999-a11y/yun.git .
cp .env.feiniu.example .env
```

编辑 `.env`，把 `MYSQL_ROOT_PASSWORD` 和 `MYSQL_PASSWORD` 改成强密码，然后启动：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

查看状态：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps
docker compose -f docker-compose.prod.yml --env-file .env logs -f backend
```

更新代码：

```bash
cd /vol1/1000/docker/xiyiyun
git pull
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

停止：

```bash
docker compose -f docker-compose.prod.yml --env-file .env down
```

## 方式二：飞牛 Docker Compose 界面

如果你不用 SSH，可以在飞牛的 Docker Compose / 项目界面里新建项目：

1. 项目名称：`xiyiyun`
2. 项目路径：建议 `/vol1/1000/docker/xiyiyun`
3. 先把仓库代码放到该目录，或用飞牛终端执行上面的 `git clone`
4. Compose 文件选择 `docker-compose.prod.yml`
5. 环境变量复制 `.env.feiniu.example` 的内容，并修改数据库密码
6. 点击部署

## 上线提醒

当前版本可用于局域网演示和流程验收。真实对外收款前，需要替换：

- 微信 / 支付宝正式商户配置
- 短信平台密钥
- 上游供应商真实 API 与签名规则
- 后台默认账号密码策略
- HTTPS 证书与正式域名
