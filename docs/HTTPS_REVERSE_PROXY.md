# HTTPS 反向代理模板

生产对外访问必须由外层 HTTPS 反向代理承接 443，再转发到本项目 Docker 暴露的 Web、H5、Admin HTTP 端口。三端容器内置 Nginx 只监听容器内 HTTP，不负责证书托管。

下面示例假设 `.env` 端口如下：

```text
WEB_PORT=18080
H5_PORT=18081
ADMIN_PORT=18088
```

请把 `shop.example.com`、`h5.example.com`、`admin.example.com` 替换成真实域名，并把同样的 HTTPS 来源写入 `.env` 的 `XIYIYUN_CORS_ALLOWED_ORIGINS`。

```nginx
map $http_upgrade $connection_upgrade {
  default upgrade;
  '' close;
}

server {
  listen 80;
  server_name shop.example.com h5.example.com admin.example.com;
  return 301 https://$host$request_uri;
}

server {
  listen 443 ssl http2;
  server_name shop.example.com;

  ssl_certificate /path/to/fullchain.pem;
  ssl_certificate_key /path/to/privkey.pem;
  add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

  location / {
    proxy_pass http://127.0.0.1:18080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
  }
}

server {
  listen 443 ssl http2;
  server_name h5.example.com;

  ssl_certificate /path/to/fullchain.pem;
  ssl_certificate_key /path/to/privkey.pem;
  add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

  location / {
    proxy_pass http://127.0.0.1:18081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
  }
}

server {
  listen 443 ssl http2;
  server_name admin.example.com;

  ssl_certificate /path/to/fullchain.pem;
  ssl_certificate_key /path/to/privkey.pem;
  add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

  allow 203.0.113.10;
  deny all;

  location / {
    proxy_pass http://127.0.0.1:18088;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
  }
}
```

如果外层代理只转发根路径到对应前端端口，本项目内层 Nginx 会继续处理 `/api/`、`/ws/` 与 `/uploads/` 到后端的转发。不要把后端容器端口直接暴露到公网。
