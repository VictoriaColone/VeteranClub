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
| POST | `/v1/auth/mock-login` | `{role,accountId?}`；用户统一传 `user`，旧身份别名仅兼容历史数据与测试 | 公开演示入口 |
| POST | `/v1/auth/logout` | 撤销当前会话 | 已登录 |
| GET | `/v1/club` | 俱乐部公开配置 | 已登录 |
| GET | `/v1/catalog/games` | 游戏和区服列表 | 已登录 |
| GET | `/v1/players?gameId=` | 用户能看到认证成员与自己的档案 | 已登录 |
| POST | `/v1/orders` | `{serviceType,gameId,region,playerId?,quantity,task?,taskPrice?,note?}` | 通过对应类型与游戏考核的用户 |
| GET | `/v1/orders` | `scope,status,serviceType,gameId,query,refund` | 服务端自动限制数据归属 |
| GET | `/v1/orders/:id` | 订单、金额、服务动态 | 下单用户／全部协作成员／员工 |
| POST | `/v1/orders/:id/pay` | 模拟支付；首次支付发布需对应发单资格，重复已支付调用幂等 | 订单下单用户 |
| POST | `/v1/orders/:id/accept` | 认领订单与占用接单容量 | 匹配成员 |
| POST | `/v1/orders/:id/assign` | `{playerId,reason?}` | 客服／管理员 |
| POST | `/v1/orders/:id/start` | 开始服务 | 接单负责人 |
| POST | `/v1/orders/:id/finish` | `{note,taskCompleted?}`；护航任务需验收，时长单需计时达标 | 接单负责人 |
| POST | `/v1/orders/:id/confirm` | 下单用户确认完成 | 订单下单用户 |
| POST | `/v1/orders/:id/cancel` | 取消未支付订单 | 订单下单用户／客服／管理员 |
| POST | `/v1/orders/:id/refund` | `{amount,reason}`，原因至少 5 字 | 订单下单用户／客服／管理员 |
| POST | `/v1/orders/:id/review-refund` | `{approve,note}` | 财务／管理员 |
| POST | `/v1/player/availability` | `{available}` | 当前成员 |
| GET | `/v1/player/wallet` | 报酬明细及批次记录 | 当前成员 |
| PATCH | `/v1/admin/players/:id` | `{price?,available?,approved:false?}`；资格授予必须通过考核接口 | 管理员 |
| GET | `/v1/applications` | 用户看本人、员工看申请列表 | 已登录 |
| POST | `/v1/applications` | `{serviceType,gameIds,introduction}` | 用户 |
| POST | `/v1/admin/applications/:id/review` | `{status,score?,note}`，pending → assessment → approved/rejected | 管理员 |
| POST | `/v1/orders/:id/add-assistant` | `{playerId}`；限已接取、未开始的护航单 | 负责人 |
| POST | `/v1/orders/:id/remove-assistant` | `{playerId}`；不能移除负责人 | 负责人 |
| POST | `/v1/orders/:id/demo-elapse` | **仅演示**：模拟走满约定时长；真实接口应移除 | 负责人 |
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

下单时保存单价、总价、服务时长和 commissionBps 快照；10000 基点为 100%。报酬向下取整到分。每位成员的份额不能重复加入结算批次，且本阶段不支持对此类订单发起退款。

真实后端仍须实现：创建订单请求幂等键、支付／退款渠道状态与回调重试、数据库事务与唯一约束、凭据管理、参数及内容审查、任务持久化、备份、渠道对账。当前浏览器模拟不替代这些能力。

## 两类订单与资格

`serviceType` 为 `escort`（护航）或 `hourly`（组队），与游戏独立。护航按整单金额 `taskPrice`（分，100～100000）计价，`quantity=1`，任务项 `task` 必须是非空字符串，最多 200 字；模拟样例默认 `100w纯带出`，可以修改；组队按单价和 1～8 小时时长计费。开始时写入 `startedAt`；时长未满拒绝提交，护航提交需 `taskCompleted:true` 和完成记录。

新用户默认无组队、护航的发单及接单资格。创建与接取订单均按服务方向和考核游戏检查，未通过时返回 403；不能接取自己发布的订单。入驻流程：提交 → 资料审核 → 专项考核 → 录入成绩（60～100 分）后开通该类型及游戏的发单与接单权限。拒绝后可重新申请。

`team` 包含负责人及副手，副手也必须通过护航与该游戏考核、在线且无未完成订单。开始后禁止添加和移除成员。团队全员能查看并在“我参与的”中看到订单；开始、完成及团队管理仅负责人执行。

`earnings` 为团队净报酬，`participantEarnings` 按人数均分；尾差按名单顺序每人补一分，保证金额守恒。成员份额分别记录 `team[].settlementId`，任一份额结算即禁止整单新退款。钱包和批次按本人份额统计。部分退款在结算前按原费率重算，再重新均分。

当前游戏目录：`delta`（三角洲，区服“国服”）、`valorant`（无畏契约）、`lol`（英雄联盟）。创建订单、申请考核只能使用目录中有效游戏；已移除的 `hok`、`pubg` 不再接受新发布。组队任务字段归为空字符串，仅按 `quantity` 小时履约；护航 `task` 拒绝缺省、空白及非字符串值，不会在接口中静默补默认任务。
