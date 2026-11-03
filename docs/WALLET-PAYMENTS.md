# 钱包与商户收款上线说明

产品1.0.1，未正式发布。2026-11-02已部署云端内部验收版本uat-20261102-wallet-01，数据库V9。微信支付适配默认关闭，不把技术适配写成渠道已开通。

## 本版边界

客户付100元给俱乐部支付商户；订单完成、等待结算且无售后后，按成员订单快照计报酬。默认成员80元、平台20元，支付手续费暂由平台承担。成员钱包记录平台应付报酬，提现审批后由经营方通过经营结算账户人工付给本人银行卡；管理后台仅登记经核对的实际银行付款，不自动划款。不是持牌支付钱包，不支持充值、消费、转账或赚取利息。

```mermaid
flowchart LR
  A[微信内客户付款] --> B[商户账户统一收款]
  B --> C[渠道验签与查单确认]
  C --> D[服务完成及确认]
  D --> E[成员待结算收入]
  E -->|满24小时且无售后| F[可提现余额]
  F -->|申请时原子冻结| G[平台审核]
  G --> H[经营方人工银行付款]
  H -->|核对流水后登记| I[已付款]
```

每人抽佣可独立设置，订单参与时锁定快照。多成员订单在同一笔订单内分配收入；本版不向支付机构提交分账。模拟收入另列，历史income_entry不回填真实钱包。

## 部署步骤

1. 先运行完整源码测试与前后端构建。使用隔离测试库，不在业务库导入模拟真实收入。
2. 在阿里云DMS用数据库管理员身份执行 [member-wallet-v9.sql](../deploy/rds/member-wallet-v9.sql)。新增7表和索引，不修改旧交易；普通norman_service账号维持SELECT/INSERT/UPDATE/DELETE权限。
3. 部署配套V9后端，启动时Flyway核验预建表、登记版本；确认健康后部署配套前端。固定命令部署通道只有上传/部署权限，不能自行获得数据库DDL权限。表未预建时不得推送替换健康的V8实例。
4. 验收环境保持微信配置关闭。新模拟完成订单只进入simulation；确认wallet.total/available均为0、不能提现。核对两端入口和后台角色边界。
5. 若实际产生真实资金，回退前必须停止资金写入并核对所有处理中提现/退款，不能直接回旧版自动结算。回退应用不删新增表，不移除交易凭据。

## 真实微信收款所需资料

由经营方申请并获批：个体工商户微信支付普通商户号、适合实际业务的类目、同主体认证服务号及关联AppID、公众号网页授权权限、正式域名ICP备案/HTTPS及可公开接收通知的服务。不得假定预览SSH域名可用于正式回调。

### 2026-11-02 商户准备进度

所有者已确认拥有商户号，但尚无同主体认证服务号，JSAPI支付权限尚未完成；商户号具体标识、账号类型、获批经营类目及产品权限未核验。不能将“已有商户号”记为“真实收款已开通”。ICP备案最新结果仍待核实。

1. 在微信商户平台“账户中心 → 商户信息”查看商户号；确认主体与实际经营主体一致。
2. 在微信公众平台注册同主体服务号并完成微信认证；认证后在“设置与开发 → 开发接口管理 → 基本配置”查看开发者ID（AppID）。这是服务号AppID，不是小程序AppID或DCloud AppID。
3. 已有商户在“产品中心 → JSAPI支付 → 申请开通”提交实际经营场景、同主体认证服务号AppID及商品/服务页面材料，按页面要求完成AppID绑定及审批。具体类目和资质以渠道审核为准。
4. 备案通过后准备正式公网HTTPS域名和普通HTTP生产构建，配置网页授权域名、JSAPI支付授权目录及通知路由。现有SSH预览入口和acceptance构建继续用于隔离模拟验收，不能直接启用真实支付。
5. 通过服务器受保护文件配置APIv3密钥、服务号AppSecret、商户私钥及微信支付公钥；不通过聊天或Git交付秘密值。配置就绪后再进行真实渠道小额支付、退款及到账核对；本轮未执行真实交易。

官方路径依据：[开发必要参数](https://pay.wechatpay.cn/doc/v3/merchant/4013070756)、[已有商户申请JSAPI权限](https://pay.wechatpay.cn/doc/v3/merchant/4012791854)、[JSAPI开发准备](https://pay.wechatpay.cn/doc/v3/merchant/4015423216)。

本版仅实现**微信内H5公众号JSAPI**。普通个体工商户的产品权限与外部浏览器H5支付不同；没有实现小程序支付或支付宝。接口开关不会替代支付机构准入。官方依据：[普通商户接入权限](https://pay.weixin.qq.com/doc/v3/merchant/4015477838)、[JSAPI开发准备](https://pay.wechatpay.cn/doc/v3/merchant/4015423216)、[官方Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)。实际准入以商户后台审核为准。

配置为Spring属性，默认关闭；建议通过仅服务账号可读的外部配置文件提供非密钥标识，密钥内容放绝对路径文件（0600），不放前端、仓库或聊天：

```properties
club.wechat.enabled=false
club.wechat.merchant-id=<获批商户号>
club.wechat.app-id=<同主体服务号AppID>
club.wechat.serial=<商户API证书序列号>
club.wechat.private-key-path=/etc/norman-pay/merchant-private.pem
club.wechat.public-key-path=/etc/norman-pay/wechatpay-public.pem
club.wechat.public-key-id=<微信支付公钥ID>
club.wechat.api-key-file=/etc/norman-pay/api-v3-key
club.wechat.app-secret-file=/etc/norman-pay/service-account-secret
club.wechat.origin=https://<正式域名>
club.wallet-hold-hours=24
```

启用时必须非development、cookieSecure、标准HTTPS443域名且在club.allowed-origins。AppSecret文件为公众号密钥，APIv3密钥32字节；支付公钥与商户私钥不能混用。启用缺项直接拒绝启动。配置轮换需保留已在途交易可验证性，不能随意替换商户或AppID。

公网路由：支付通知`POST /api/v1/payments/notify/wechat`；网页授权回调`GET /api/v1/payments/wechat/oauth/callback`。在公众号配置网页授权域名，在商户后台配置JSAPI支付授权目录（支付页实际路径下的`/mobile/`，以渠道校验为准）。通知路由无需用户登录，但必须微信签名与加密数据校验；回调需要当前Cookie会话和一次性state，不能跳过认证。

反向代理针对OAuth回调关闭access_log，或仅记录不含查询参数的`$uri`，不要记录`$request_uri`中的code/state。服务端不记录OAuth响应、签名原文、openid、银行卡号、API密钥或密码。禁止把支付页面/授权回调做CDN公共缓存。Webhook不要经过登录跳转或验证码页。

## 启用前联调

用获批测试条件/小额实单核实：OAuth同会话与过期/重放、预下单金额、微信拉起/取消/响应丢失、通知验签失败/重复通知、主动查单、10分钟关单、迟到收款和重复收款补偿、全额/部分退款及重试、多人报酬舍入、24小时释放、并发提现、驳回/撤销、银行结果不明保持冻结、实际银行流水唯一性、付款登记重复提交、敏感账户查看及审计。

收款银行账户当前只有格式及填报姓名一致性检查，付款前需通过可靠业务流程核实本人账户；本次未接银行四要素。生产财税、劳务协议、代扣代缴及银行付款凭据由经营方落实，本版未实现自动税务申报。所有支付结果均以服务端核验为准；前端WeixinJSBridge成功不直接记收款。

## 验证及状态

自动化覆盖隔离H2的真实/模拟分离、历史不导入、银行卡加密与越权、24小时等待、幂等与并发提现、审核/付款状态约束、重复流水、退款冲账；官方SDK测试用临时RSA密钥/AES-GCM密文验证通知，无真实交易。前端覆盖请求号及HTTP头、Mock拒绝真实银行卡/提现。完整构建记录见artifacts/wallet-*日志。

尚缺真实商户/服务号资料及渠道联调；未产生真实收款或银行转账。此前浏览器权限阻塞已在客户端真正重启后解除；本轮已通过DMS执行新增表/索引、部署配套前后端并完成浏览器验收。

本轮最终结果：前端113项、普通后端79项、验收后端9项通过；另6项钱包用例在H2默认REPEATABLE_READ连接池下通过。源码类型检查和三个前端构建通过。部署包为`artifacts/uat-20261102-wallet-01.tar.gz`，校验值见同目录`.sha256`；结构迁移成功后使用现有固定命令部署客户端：

```sh
artifacts/ops-venv/bin/python deploy/rds/ssh-deploy.py --bundle artifacts/uat-20261102-wallet-01.tar.gz --release uat-20261102-wallet-01
```

该命令本轮**已执行成功**；此前已在原norman_acceptance库执行7表DDL并核验索引与约束。后端启动后Flyway版本9，固定命令通道与健康接口均确认服务正常，用户钱包及后台提现页面可打开。真实微信开关保持false。

部署包SHA256：`068e3ae742593677e756b97553dc8fd2e5c2e689a7d82362e0ee859e205774b7`。云端结构核验：7张InnoDB表、52字段、10唯一键、5查询索引、3资金CHECK；未回填历史模拟收入。验收截图和机器可读元数据见`artifacts/wallet-cloud-*.jpg`与`artifacts/wallet-validation.json`。
