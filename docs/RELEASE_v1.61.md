# 喜易云 v1.61 更新说明

发布日期：2026-09-13

## 版本定位

`v1.61` 修复"当前网络登录尝试过于频繁"导致全站无法登录的问题：真实客户端 IP 不再被容器层覆盖，
按 IP 的登录频率计数不再永久驻留。

## 问题原因

- `apps/{admin,web,h5}/nginx.conf` 把 `X-Forwarded-For` 覆盖写成容器看到的对端地址，
  即 Docker 网关 `172.18.0.1`，宿主机 nginx 传来的真实客户端 IP 被丢弃。
- 结果是三端所有用户共用同一个 IP 限流桶，任一来源刷满阈值（默认 5 分钟 30 次），
  全部用户一起被拒，且 `admin.xiyi.co` 的 vhost 完全没有写 `X-Forwarded-For`。
- `RedisSecurityStateStore.incrementLoginIpAttempt` 只在计数为 1 时设置 TTL，
  一旦该次 `EXPIRE` 失败或键被重建，计数键就永久驻留（`TTL=-1`），阈值再也无法回落。

## 主要更新

- 容器内 nginx 改为透传 `X-Forwarded-For` / `X-Real-IP`，仅在宿主机未提供时回退到 `$remote_addr`。
- 宿主机 nginx 的 `web.xiyi.co` / `h5.xiyi.co` / `admin.xiyi.co` / `www.xiyi.co`
  统一用 `$remote_addr` 覆盖写入 `X-Forwarded-For`，客户端自带的伪造前缀不再被信任。
- IP 计数键补 TTL 自愈：键已存在但无 TTL 时补设窗口，避免计数永不过期把整站锁死。
- H5 登录/短信登录接口在触发登录频率限制时返回业务提示，不再抛 500。

## 数据库升级

无数据库变更。

## 验证范围

- 无 TTL 的 IP 计数键在下次计数时自动补上 TTL；已有窗口的键不会被延长。
- 三端 nginx 配置语法通过，宿主机 `nginx -t` 通过。
- 登录链路按真实客户端 IP 计数，登录成功/失败与限流提示行为不变。
- 后端完整单元测试通过。
- `git diff --check` 通过。
