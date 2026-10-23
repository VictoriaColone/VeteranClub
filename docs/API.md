# 模拟 API 契约

客户端：`packages/core/src/api.ts`。默认由 `createMockTransport` 处理异步方法调用，**浏览器 Network 面板不会出现这些 HTTP 请求**。切换 HTTP Transport 后才请求真实后端。

通用响应：

```json
{"code":0,"message":"ok","data":{},"requestId":"req-example"}
```

错误由 Mock 抛出 `ApiError(code, message)`，HTTP 后端应返回相应 HTTP 状态和同结构错误体。约定 400 为业务校验，401 为未登录，403 为无权限，404 为不存在。当前模拟 HTTP 登录会话通过 `Authorization: Bearer <token>` 传递；生产 H5 也可改为 HttpOnly Cookie 并配置 CSRF 防护。

所有金额为整数「分」，所有时间为 Unix 毫秒，前端按 Asia/Shanghai 格式化。

| 方法 | 路径 | 参数 / 用途 | 允许身份 |
|---|---|---|---|
| POST | `/v1/auth/mock-login` | `{role}`，仅演示 | 公开演示入口 |
| POST | `/v1/auth/logout` | 撤销当前会话 | 已登录 |
| GET | `/v1/club` | 俱乐部公开配置 | 已登录 |
| GET | `/v1/catalog/games` | 游戏和区服列表 | 已登录 |
| GET | `/v1/players?gameId=` | 老板仅能看到审核通过的打手 | 已登录 |
| POST | `/v1/orders` | `{gameId,region,playerId?,quantity,note?}` | 老板 |
| GET | `/v1/orders` | `scope,status,gameId,query,refund` | 服务端自动限制数据归属 |
| GET | `/v1/orders/:id` | 订单、金额、服务动态 | 关联老板／打手／员工 |
| POST | `/v1/orders/:id/pay` | 模拟支付，重复调用幂等 | 订单老板 |
| POST | `/v1/orders/:id/accept` | 认领订单与占用接单容量 | 匹配打手 |
| POST | `/v1/orders/:id/assign` | `{playerId,reason?}` | 客服／管理员 |
| POST | `/v1/orders/:id/start` | 开始服务 | 接单打手 |
| POST | `/v1/orders/:id/finish` | `{note}`，至少 2 字 | 接单打手 |
| POST | `/v1/orders/:id/confirm` | 老板确认完成 | 订单老板 |
| POST | `/v1/orders/:id/cancel` | 取消未支付订单 | 订单老板／客服／管理员 |
| POST | `/v1/orders/:id/refund` | `{amount,reason}`，原因至少 5 字 | 订单老板／客服／管理员 |
| POST | `/v1/orders/:id/review-refund` | `{approve,note}` | 财务／管理员 |
| POST | `/v1/player/availability` | `{available}` | 当前打手 |
| GET | `/v1/player/wallet` | 报酬明细及批次记录 | 当前打手 |
| PATCH | `/v1/admin/players/:id` | `{price?,approved?,available?}` | 管理员 |
| GET | `/v1/admin/dashboard` | 指标、趋势、分布、动态 | 员工 |
| GET | `/v1/admin/settlements` | 结算批次 | 财务／管理员 |
| POST | `/v1/admin/settlements` | `{playerId?}` | 财务／管理员 |
| POST | `/v1/admin/settlements/:id/pay` | 模拟付款，拒绝重复操作 | 财务／管理员 |
| GET | `/v1/admin/audit` | 最近 300 条模拟审计 | 管理员 |
| PATCH | `/v1/admin/settings` | `{name,commissionBps,unpaidTimeoutMinutes,announcement}` | 管理员 |
| POST | `/v1/demo/reset` | 恢复样例，仅演示 | 管理员 |

## 状态与约束

```text
unpaid → waiting → accepted → serving → confirming → completed
unpaid → cancelled
```

退款单独使用 `none → pending → refunded / rejected`；全额退款使订单变为 cancelled，部分退款保留履约状态并重算报酬。待审售后阻止接单、开始、提交完成、确认及结算。超时未支付由模拟请求访问时检查；真实服务应改为可靠定时任务。

下单时保存单价、总价、服务时长和 commissionBps 快照；10000 基点为 100%。报酬向下取整到分。已进入结算批次的订单不能重复加入批次，且本阶段不支持对此类订单发起退款。

真实后端仍须实现：创建订单请求幂等键、支付／退款渠道状态与回调重试、数据库事务与唯一约束、凭据管理、参数及内容审查、任务持久化、备份、渠道对账。当前浏览器模拟不替代这些能力。
