# 喜易云 v1.29 更新说明

发布日期：2026-07-30

## 版本定位

`v1.29` 为阿奇索 / 91卡券标准货源兼容版本，向下游提供直充与卡密供货能力。

## 主要更新

- 新增阿奇索固定路径的 AppId、商品列表、商品详情、直充下单、卡密下单、订单查询和撤单接口。
- 按标准货源规则实现全字段 ASCII 排序、空值参与、商户密钥前后拼接和 32 位大写 MD5 签名。
- 商品类型映射为 `1 = 直充`、`2 = 卡密`，并复用现有会员分组、商品开放范围、余额支付和订单幂等能力。
- 支持文档顶层 `account` 与测试工具 `attach` 两种直充字段格式。
- 支持最大整单成本校验，超出时返回错误码 `1220`。
- 卡密订单返回 Base64 编码的 UTF-8 JSON 数组，并支持订单终态与撤单结果回调。
- 回调地址限制为 91卡券和阿奇索官方域名；仅标准文档指定的 `cb.acpr.agiso.com` 允许 HTTP。
- AppId 与 AppSecret 通过生产环境变量注入，不写入代码库。
- Admin Nginx 新增 `/agisoAcprSupplierApi/` 后端转发，解决外部 POST 请求被静态站点拒绝的问题。
- Admin、H5 与桌面 Web Nginx 同步增加 `/dockapiv3/` 转发，三端域名均可承接咔咔云兼容协议。
- 新增会员开放 API 的 Markdown / 纯文本开发文档，以及多协议对外供货开发文档。
- 补齐用户名功能合并后的两处构造参数，会员状态更新继续保留用户名，相关后端测试恢复编译。
- 补齐全新数据库用户名列、集成测试迁移清单和生产 `008` 升级步骤，避免持久化会员查询降级为不存在。
- 保留生产 `v1.27.2` 的商品通道快照复用热修复，避免商品列表按商品重复读取通道配置。

## 固定接口路径

- `POST /agisoAcprSupplierApi/app/getAppId`
- `POST /agisoAcprSupplierApi/product/getList`
- `POST /agisoAcprSupplierApi/product/getTemplate`
- `POST /agisoAcprSupplierApi/order/createRecharge`
- `POST /agisoAcprSupplierApi/order/createPurchase`
- `POST /agisoAcprSupplierApi/order/get`
- `POST /agisoAcprSupplierApi/order/cancel`

## 验证记录

- 后端完整测试套件：302 项通过，0 失败、0 错误。
- 阿奇索签名、商品、直充、卡密、金额保护、幂等、回调定向测试：9 项通过，0 失败。
- 用户名数据库初始化、幂等迁移、并发余额、并发库存和完整订单链回归：通过。
- 数据库全新安装与老库升级双路径：33 张表 / 566 项结构无差异，28 个 Mapper 校验通过。
- H5、桌面 Web、Admin 三端生产构建：通过。
- Admin Nginx 配置语法：通过。
- `git diff --check`：通过。

## 已知边界

- 已终态订单会立即回调；长期处理中订单可通过订单查询接口获取最新状态。
- 跨重启的持久化回调重试队列尚未实现。
- 卡密加密采用 Base64 封装 JSON 数组，正式联调时需要将该规则提供给阿奇索客服。

## 影响范围

- 修改后端标准货源接口、回调客户端、配置与生产 Compose。
- 修改 Admin Nginx 的阿奇索与咔咔云接口转发，并同步 H5、桌面 Web 的咔咔云接口转发。
- H5 与桌面 Web 仅增加服务器端反向代理，不改变商城页面展示或购买流程。
