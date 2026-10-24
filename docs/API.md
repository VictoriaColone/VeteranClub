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
| GET | `/v1/club` | 俱乐部公开信息与固定规则：20% 服务费、待支付 10 分钟关闭 | 已登录 |
| GET | `/v1/catalog/games` | 游戏和区服列表 | 已登录 |
| GET | `/v1/players?gameId=` | 用户能看到认证成员与自己的档案 | 已登录 |
| GET | `/v1/products?scope=public/mine` | 在售目录或本人商品 | 用户 |
| POST | `/v1/products` | `{gameId,serviceType,title,description,price,task?}`，价格为分 | 对应资格用户 |
| PATCH | `/v1/products/:id` | `{active}`，商品上下架 | 本人 |
| POST | `/v1/orders/grab` | `{serviceType,gameId?}`，接取最早可接公共单 | 对应资格用户 |
| POST | `/v1/orders` | `{serviceType,gameId,region,playerId?,productId?,quantity,task?,taskPrice?,note?}` | 所有用户（无需考核） |
| GET | `/v1/orders` | `scope,status,serviceType,gameId,query,refund` | 服务端自动限制数据归属 |
| GET | `/v1/orders/:id` | 订单、金额、服务动态 | 下单用户／全部协作成员／员工 |
| POST | `/v1/orders/:id/pay` | 模拟支付；重复调用幂等，商品订单付款前重新核验商品及发布者资格 | 订单下单用户 |
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
| GET | `/v1/admin/users` | 全部注册用户（含未下单用户），按 userId 关联订单与入驻申请 | 员工 |
| PATCH | `/v1/admin/players/:id` | `{price?,available?,approved:false?}`；资格授予必须通过考核接口 | 管理员 |
| GET | `/v1/applications` | 用户看本人、员工看申请列表 | 已登录 |
| POST | `/v1/applications` | `{serviceType,gameIds,introduction}` | 用户 |
| POST | `/v1/admin/applications/:id/review` | `{status,score?,note}`，pending → assessment → approved/rejected | 管理员 |
| POST | `/v1/orders/:id/add-assistant` | `{playerId}`；限已接取、未开始的护航单 | 负责人 |
| POST | `/v1/orders/:id/remove-assistant` | `{playerId}`；不能移除负责人 | 负责人 |
| POST | `/v1/orders/:id/demo-elapse` | **仅演示**：模拟走满约定时长；真实接口应移除 | 负责人 |
| GET | `/v1/admin/dashboard` | 指标、趋势、分布、动态 | 员工 |
| GET | `/v1/admin/settlements` | 自动结算记录，只读查询 | 财务／管理员 |
| GET | `/v1/admin/audit` | 最近 300 条模拟审计 | 管理员 |
| POST | `/v1/demo/reset` | 恢复样例，仅演示 | 管理员 |

## 状态与约束

```text
unpaid → waiting → accepted → serving → confirming → completed
unpaid → cancelled
```

退款单独使用 `none → pending → refunded / rejected`；全额退款使订单变为 cancelled，部分退款保留履约状态并重算报酬。待审售后阻止接单、开始、提交完成、确认及结算。待支付订单满 10 分钟自动关闭；Mock 在请求时检查，真实服务应改为可靠定时任务。

新订单固定使用 2000 基点（20%）服务费。旧本地配置同步为 20% / 10 分钟，历史订单的费率与金额快照保持不变。下单时保存单价、总价、服务时长和 commissionBps 快照；10000 基点为 100%。报酬向下取整到分。用户确认完成后，同一次模拟订单操作自动为每位成员生成已结算记录。每个订单、成员份额只结算一次；待审售后暂停结算，审核结束且订单已完成后自动继续。旧版未付款批次在满足条件时自动完成，已有已付款记录不重复付款。本阶段不支持对已结算订单再次发起退款。

后台从客户管理中的用户行查看入驻进展，管理员可继续资料审核、录入考核成绩与拒绝申请；其他员工只读查看。独立入驻考核、报酬结算及俱乐部设置页面已移除。旧手动生成结算、手动付款和修改配置接口返回 410，客户端不再暴露这些写方法。所有结算仍为模拟记录，不进行真实微信或银行转账。

真实后端仍须实现：创建订单请求幂等键、支付／退款渠道状态与回调重试、数据库事务与唯一约束、凭据管理、参数及内容审查、任务持久化、备份、渠道对账。当前浏览器模拟不替代这些能力。

## 两类订单与资格

`serviceType` 为 `escort`（护航）或 `hourly`（组队），与游戏独立。护航按整单金额 `taskPrice`（分，100～100000）计价，`quantity=1`，任务项 `task` 必须是非空字符串，最多 200 字；模拟样例默认 `100w纯带出`，可以修改；组队按单价和 1～8 小时时长计费。开始时写入 `startedAt`；时长未满拒绝提交，护航提交需 `taskCompleted:true` 和完成记录。

新用户默认无组队、护航的商品发布及接单资格。发布商品与接取订单按服务方向、考核游戏检查，未通过返回 403；普通用户下单和支付无需资格。不能接取自己的订单。入驻流程：提交 → 资料审核 → 专项考核 → 录入成绩（60～100 分）后开通该类型及游戏的商品发布与接单权限。拒绝后可重新申请。

`team` 包含负责人及副手，副手也必须通过护航与该游戏考核、在线且无未完成订单。开始后禁止添加和移除成员。团队全员能查看并在“我参与的”中看到订单；开始、完成及团队管理仅负责人执行。

`earnings` 为团队净报酬，`participantEarnings` 按人数均分；尾差按名单顺序每人补一分，保证金额守恒。成员份额分别记录 `team[].settlementId`，任一份额结算即禁止整单新退款。钱包和批次按本人份额统计。部分退款在结算前按原费率重算，再重新均分。

当前游戏目录：`delta`（三角洲，区服“国服”）、`valorant`（无畏契约）、`lol`（英雄联盟）。创建订单、申请考核只能使用目录中有效游戏；已移除的 `hok`、`pubg` 不再接受新发布。组队任务字段归为空字符串，仅按 `quantity` 小时履约；护航 `task` 拒绝缺省、空白及非字符串值，不会在接口中静默补默认任务。

## 商品、快速下单和公共大厅

商品包含发布者、游戏、服务类型、标题、说明、价格与护航任务项。发布本身不改变订单或钱包。`productId` 下单时从服务端核验商品在售、资格有效，强制绑定商品发布者、价格与任务，防止客户端篡改；订单保留商品标题与价格快照。已下架或资格失效商品不能创建新订单或支付未付款商品订单。

快速订单默认无 `playerId`，付款后进入公共大厅。指定 `playerId` 或购买指定商品的订单不会出现在 `scope=hall` 中，而在对应成员本人订单列表中作为待接受邀请返回。仅指定成员及下单用户／员工可查看，其他用户不可查看。

公共大厅仅返回已付款、待接单、无指定成员且无待审售后的订单。抢单接口同步执行筛选和接取，排除本人订单，校验服务类型、游戏、在线状态及未完成订单容量。Mock 使用同源 Web Locks 串行化；真实后端必须使用数据库事务和唯一约束保证竞争下只有一个成功者。


## 微信小程序传输适配

小程序默认通过原生存储适配器调用相同 Mock API，不发送网络请求。用户会话也保存在本小程序中，H5 与小程序数据相互独立。

将来切换 HTTP 模式时，微信 `uni.request` 不支持直接发送 PATCH：移动端适配器改发 POST，并带 `X-HTTP-Method-Override: PATCH`。**后端需要显式支持该约定**，在鉴权、权限校验与路由分发中按原业务 PATCH 处理，不能把覆盖头当作绕过授权的依据。H5 保留原 PATCH 请求。请求超时 15 秒，网络失败或不合法响应不会报告支付成功。参见 [uni.request 官方方法支持表](https://uniapp.dcloud.net.cn/api/request/request.html)。

真实微信登录及支付仍待服务端实现：小程序用临时 code 交换服务端会话，订单支付由服务端创建交易并返回客户端调起参数，最终以已验签回调及服务端查单为准。不能让生产小程序调用 mock-login 或通过客户端 `/pay` 改成已付款。详细边界与验收见 `WEIXIN.md`。

## 长期个人服务与邀约

商品在售状态与成员在线、忙碌状态分开。商品可被多次下单，创建与完成订单不消耗或自动下架商品；成员正在服务或离线时，仍允许创建并支付指定邀约（商品下单及快速下单指定成员）。GET /v1/players 的 available 与 busy 用于展示可立即服务状态；busy 根据已接取、服务中、待用户确认的参与订单计算。创建订单继续检查商品在售、对应类型及游戏资格和禁止自购；付款继续复核商品及资格。接取邀请必须在线且无未完成参与订单，成功后才占用服务容量；不会因为付款自动开始服务。发布者下架后禁止新下单及未支付商品订单付款，不影响已经付款的邀约履约。
