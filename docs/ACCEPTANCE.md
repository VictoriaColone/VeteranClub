# 服务器内部验收

2026-11-03访问方式更新：管理后台已切换至`https://admin.veteranclub.icu:9443/admin/`，须连接已授权设备的WireGuard VPN，仍需员工账号登录。原SSH转发/preview域名中的后台页面、员工登录与管理API已关闭；本文下方涉及后台旧地址的历史记录不再作为现行入口。用户端继续使用原有SSH预览方式。安装、Windows/Mac客户端和撤销方法见[员工VPN手册](STAFF-VPN.md)。

尚未完成域名实名、备案和短信／商户资料时，可先部署独立验收环境。当前采用服务器本机监听＋SSH 隧道；不开放网站或数据库的公网端口，不修改 DNS。阿里云对中国内地网站正式对外服务的要求见[使用须知](https://help.aliyun.com/zh/simple-application-server/product-overview/usage-notes)。

验收环境用于检查页面、API、数据库和后台任务的完整链路。**用户体验账号和收付款为验收样例**，数据写入服务器 `norman_acceptance` 数据库，用户端和员工后台共用；与浏览器 Mock、开发库 `norman_club` 及未来生产库相互独立。不要录入真实客户资料。

## 打包与隔离

```bash
npm run server:build:acceptance
npm run build:acceptance
```

- 服务端验收 JAR：`server/target/acceptance/norman-acceptance.jar`。
- 前端验收产物：`dist-acceptance/`。
- 普通 `npm run server:build` 不包含 `src/acceptance/` 的任何身份切换或模拟付款入口；验收编译目录也单独隔离，防止残留 class 混入正式 JAR。
- 验收包启动要求：显式 `ACCEPTANCE_MODE=true`、开发模式、仅监听 `127.0.0.1`、指定的本机来源、独立 `norman_acceptance` 数据库、真实短信关闭。
- 验收会话仍使用 HttpOnly Cookie、CSRF、角色权限与业务事务。用户端可切换预设体验账号。管理后台必须通过账号密码登录，不提供员工免密身份；旧演示员工账号和会话已停用。
- 微信、支付宝仍是未配置状态；模拟付款使用单独的 `acceptance` 渠道，不能接收外部支付通知。退款由验收渠道模拟确认；确认完成后只自动记账，真实转账仍待接入。
- 组队订单的“模拟走满时长”只存在于验收包；正式包按服务器时钟履约。
- 页面顶部明确显示“服务器验收／内部验收、模拟收付款”，数据不会保存在浏览器业务数据库中。

预设用户：林序（无资格）、小屿（仅组队）、凛（仅护航）、岚（双资格）、远山（双资格，可作为副手）。可以用林序提交入驻申请，在后台客户管理审核、安排考核并录入成绩；资格变化写入真实数据库。

## 服务器结构

已有环境迁出本地 MySQL、改接阿里云 RDS 的步骤见 [RDS 迁移](RDS.md)。完成迁移后使用 RDS 连接配置及更新脚本，以下本地数据库安装方式仅用于迁移前或新建的本地库验收环境。

部署脚本在 `deploy/acceptance/install.sh`，Ubuntu 24.04 需先安装 Java 21、Docker 与 Nginx。数据库默认采用 Docker 官方镜像的 [Amazon ECR Public 分发源](https://www.docker.com/press-release/docker-official-images-available-amazon-elastic-container-registry/) `public.ecr.aws/docker/library/mysql:8.4`；也支持通过 `MYSQL_ACCEPTANCE_IMAGE` 指定 [MySQL 官方 Oracle 镜像](https://dev.mysql.com/doc/refman/8.4/en/docker-mysql-getting-started.html) `container-registry.oracle.com/mysql/community-server:8.4`。先拉取镜像再安装。只将 `127.0.0.1:3308` 映射到容器，API 监听 `127.0.0.1:8091`，Nginx 监听 `127.0.0.1:8088`。

上传验收 JAR、前端目录与 `deploy/acceptance/` 文件组成的归档包，以及安装脚本。校验 SHA-256 后运行：

```bash
sudo docker pull public.ecr.aws/docker/library/mysql:8.4
sudo bash /home/admin/install.sh /home/admin/norman-acceptance.tar.gz <新的版本标识>
```

脚本在服务器上生成独立随机数据库密码和加密密钥，保存到权限 600 的 `/etc/norman-acceptance/`，不从本机复制生产凭据。首次创建 MySQL 数据目录，后续发布保留目录和配置；不清空数据。服务通过 systemd 开机自启，JVM 堆上限 256MiB、进程内存上限 640MiB，MySQL 容器上限 512MiB。数据库就绪检查通过实际应用账号连接目标数据库，避免误把临时初始化进程判定为可用。

| 内容 | 路径 |
|---|---|
| 当前版本 | `/opt/norman-acceptance/current` |
| 历史版本 | `/opt/norman-acceptance/releases/` |
| MySQL 持久化目录 | `/var/lib/norman-acceptance/mysql/` |
| API 私密配置 | `/etc/norman-acceptance/api.env` |
| MySQL 私密配置 | `/etc/norman-acceptance/mysql.env` |
| API 日志 | `/var/log/norman-acceptance/server.log` |
| 服务 | `norman-acceptance.service`、`norman-acceptance-mysql` 容器 |

## 私人访问

使用专门的受限 SSH 账号 `norman-preview`，只允许转发服务器 `127.0.0.1:8088`，不授予 shell、sudo、密码登录、代理转发或其他目标端口。添加此账号／公钥前需要所有者确认，具体脚本为 `deploy/acceptance/allow-tunnel.sh`；账号凭据与私钥不会写入公共仓库。主机指纹应通过已认证的阿里云 Workbench 核对，禁止跳过验证。

本机私钥保存在 `.local/acceptance-tunnel`（权限 600），已核验主机公钥在 `.local/aliyun-host-key`。停止其他占用 4190 的本地预览后，可以用系统 SSH：

```bash
ssh -N -i .local/acceptance-tunnel -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile=.local/aliyun-host-key \
  -L 127.0.0.1:4190:127.0.0.1:8088 norman-preview@<服务器公网IP>
```

或使用 `deploy/acceptance/tunnel.py`（需安装 Paramiko）：

```bash
python3 deploy/acceptance/tunnel.py --host <服务器公网IP> \
  --key .local/acceptance-tunnel --known-hosts .local/aliyun-host-key
```

本机已经准备好独立 Python 依赖与 `.local/acceptance-tunnel.json` 后，可直接运行 `npm run server:tunnel`。其他电脑需要先建立 `artifacts/ops-venv` 虚拟环境并安装 `deploy/acceptance/requirements-ops.txt`，通过安全方式取得属于自己的受限访问密钥，并独立核验服务器指纹；不要把这台电脑的私钥发给他人。

保持隧道运行后，本机访问 `http://127.0.0.1:4190/mobile/` 与 `/admin/`，内容实际由云服务器提供。这个本地链接不能直接分享给朋友；隧道关闭后停止访问。

## 检查、数据库与备份

需要在网页中浏览数据库表、筛选记录、查询 SQL 或导入导出，参见[数据库可视化管理](DATABASE-UI.md)。它通过同一 SSH 隧道的 `/database/` 入口访问，使用现有 MySQL 账号登录。

在服务器执行以下命令：

```bash
systemctl status norman-acceptance --no-pager
sudo docker ps --filter name=norman-acceptance-mysql
curl --fail http://127.0.0.1:8091/actuator/health
python3 /opt/norman-acceptance/current/deploy/check.py
```

最后一个脚本创建一笔明确标注的测试护航订单，完成模拟支付、接单、添加副手、开始、任务验收、用户确认，并检查后台读到同一订单及自动记账状态；不会实际收付款。

只读查看表与最近订单（密码在容器内部传递，不在命令行填写）：

```bash
sudo docker exec norman-acceptance-mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" norman_acceptance --execute="SHOW TABLES; SELECT id,status,amount,created_at FROM club_order ORDER BY created_at DESC LIMIT 20;"'
```

备份：`sudo bash /opt/norman-acceptance/current/deploy/backup.sh`，文件在 `/var/backups/norman-acceptance/`。这只是服务器本地备份，不替代异地备份；当前不自动上传 OSS 或购买备份服务。配置目录和加密密钥也应由所有者另行安全备份。

物料齐备后使用普通正式 JAR、独立生产数据库、真实认证及 HTTPS 配置上线；禁止将验收包直接改成公网监听。保留验收环境，便于后续发布前回归验证。


## 电脑专用 SSH 部署授权（2026-10-28，已授权并验证）

现有 `norman-preview` 继续只转发预览网站，包括已授权的两台手机。本次为本机单独生成 `.local/norman-deploy`（私钥600、被Git忽略）及公钥，已授权新系统账号 `norman-deploy`。这项授权允许替换项目代码和重启应用，持有密钥者因此具备该应用运行及其已有数据访问能力；应只保存于可信开发电脑。它不提供通用 shell、任意 sudo、SFTP、端口转发、数据库管理、操作系统配置或手机部署权限。

| 动作 | 固定范围 |
|---|---|
| `status` | 当前项目发布标识及API健康状态，不读取配置内容 |
| `upload <sha256> <size>` | SSH标准输入传输发布归档；最多80MiB，必须匹配实际长度和SHA-256；进入root专属缓存目录 |
| `deploy <release> <sha256>` | 只提取应用JAR及web文件到 `/opt/norman-acceptance/releases`；切换current，只重启 `norman-acceptance`；健康失败恢复旧应用 |

协议不执行上传包里的管理脚本；包内不能包含deploy脚本、密钥或环境文件。拒绝绝对/穿越路径、软硬链接、设备文件、重复项、控制字符、过大展开量和危险权限；并发发布互斥。每次命令最多180秒，展开最多200MiB/5000项，上传缓存总量512MiB，额度满由管理员处理。root审计记录位于 `/var/log/norman-deploy.log`，只记发布元数据和结果。此通道不执行DDL，也不自动回滚数据库；迁移仍先由数据库管理员安排，发布必须先核对已准备的数据库结构（当前V8）。

首次需要服务器现有管理员执行安装：将为本机生成的 `artifacts/norman-deploy-access-20261028.sh` 上传至Workbench的 `/home/admin/`，按其配套清单核对SHA-256，再 `sudo bash /home/admin/norman-deploy-access-20261028.sh`。安装包只含公钥、固定命令worker和安装脚本，不含私钥。安装器校验sshd/sudoers后只reload SSH，不改变预览账号、公钥或网站端口；仅安装授权，不会立即发布应用。

维护源代码：`deploy/rds/install-deploy-access.sh`、`ssh-deploy-worker.py`。worker由root持有、Python隔离模式运行，SSH使用ForceCommand；sudo只允许调用这个校验参数的固定worker。安装前审核这些文件，不能给账号添加任意shell或通用sudo规则。服务器首次安装完成后，电脑运行：

```bash
npm run server:deploy -- --status
npm run server:deploy -- --bundle artifacts/request-security-deploy-20261028.tar.gz --release uat-20261028-request-security-01
```

客户端使用独立部署密钥、已核验 `.local/aliyun-host-key`，禁止自动接受新主机指纹。源码 `deploy/rds/ssh-deploy.py`；依赖复用既有 `artifacts/ops-venv`。后续由本机构建应用JAR和H5，将JAR及前端目录打包后使用新发布标识部署；重复同一发布标识仅在相同哈希且已经是current时幂等返回。健康失败保留失败发布用于排查，使用新标识再发布。撤销由管理员清空 `/etc/ssh/authorized_keys/norman-deploy`，阻止新登录；正在执行的操作需等待退出或由管理员处理。

参考：[OpenSSH ForceCommand](https://man.openbsd.org/sshd_config#ForceCommand)、[Python tarfile安全说明](https://docs.python.org/3/library/tarfile.html#extraction-filters)。

验证：7项隔离部署测试通过，覆盖命令注入、长度/哈希、穿越/链接/设备/脚本、权限、回退、幂等/篡改和并发；安装脚本shell语法、Python编译与实际44MB发布包安全提取通过。所有者已通过Workbench完成首次管理员授权；已验证固定命令SSH登录、上传和实际部署，云端当前版本为 `uat-20261028-request-security-01`，健康UP。产品仍1.0.1未正式发布，Flyway仍V7。

## 私下域名 HTTPS 预览（2026-10-30）

所有者已确认 Let's Encrypt v1.8 协议及现有预览公钥增加一个 HTTPS 转发目标。`preview.veteranclub.icu` 的 A 记录为 `127.0.0.1`，TTL 600 秒；每台设备访问自己的本地 SSH 转发，服务器未开放公网 Web 监听。根域名及 `www` 未添加网站 A 记录。

- 用户端：`https://preview.veteranclub.icu:8443/mobile/`
- 管理后台：`https://preview.veteranclub.icu:8443/admin/`
- 原 `http://127.0.0.1:4190/mobile/` 与 `/admin/` 保留。

电脑在仓库目录运行 `npm run server:tunnel:https`，仅监听 `127.0.0.1:8443`。关闭进程、电脑休眠或 SSH 断线后需重新启动通道。SSH 主机、公钥及指纹验证沿用现有配置，私钥不复制到其他设备。安卓在原 ConnectBot 主机中新建“本地（Local）”转发：源端口 `8443`，目标 `127.0.0.1:8443`；若可选绑定地址，应为 `127.0.0.1`。保存后断开重连 SSH，再打开上述地址，并保持连接。电脑浏览器已实际验证；2026-10-30所有者确认安卓经ConnectBot断开重连后成功打开用户端。

服务器 `/etc/nginx/conf.d/norman-private-preview.conf` 仅监听 `127.0.0.1:8443`，转发原本机 `8088` 网站。网关检查精确 Host，并拒绝不匹配的 Origin；仅将 `https://preview.veteranclub.icu:8443` 翻译为原后端已信任的本机来源，后端会话、CSRF、归属和权限校验继续执行。新 HTTPS 入口为会话及 CSRF Cookie 追加 Secure 属性。应用构建、后端白名单和验收包保护未修改。

`norman-preview` 的现有公钥保留 restrict、本地转发及禁止命令限制，仅允许目标 `127.0.0.1:8088` 和 `127.0.0.1:8443`；未新增公钥、命令、sudo、SFTP、数据库或公网监听权限。原配置及公钥文件的恢复副本在服务器 root 专属 `/var/backups/norman-private-https/`，不包含业务订单备份。

当前免费证书到期日为 **2027-01-28**，私钥仅保存在服务器的 root 管理目录。由于手工 DNS 验证，**当前不能自动续期**；到期前需按证书客户端流程重新验证 DNS、签发证书，再校验并 reload Nginx。`_acme-challenge.preview` TXT 是公开域名验证记录，本文不保存其值。此配置不代表 ICP 通过、正式发布、真实短信或支付已启用。

维护源：`deploy/acceptance/enable-private-https.py`、`tunnel.py`、`scripts/acceptance-tunnel.mjs`。安装器校验证书和原 SSH 范围；更改后检查 Nginx/SSH 语法，仅 reload，失败恢复原配置。三项限制范围单元测试通过；页面/API 200、TLS 校验成功；错误 Host/Origin 403；公网 8443 不可达；原 4190 健康 UP。产品仍 **1.0.1，未正式发布**，未更新应用发布包或数据库。


## 身份登记与账号安全验收（2026-10-31）

产品保持1.0.1未正式发布。通过DMS为club_user预建身份密文、证件HMAC唯一索引、同意记录、用户密码Hash及注销字段，为sms_code预建purpose；应用保持原有DML权限，Flyway登记V8。没有变更域名、TLS网关、短信配置、模拟支付或预览公钥权限。

当前客户端仍为验收身份，真实短信未启用；身份表单是真实服务器存储入口，但当前只是用户填报、未联网核验。禁止给既有体验账号自动填入虚构成年证件；普通账号未登记成年且未入驻会被拦截，已入驻按所有者选择豁免。云端UI验收仅打开表单、检查提示与进入确认招募，不提交证件或订单；密码/注销使用隔离的H2和RecordingSms测试，不对验收账号做实际注销。

服务器结构迁移后，旧代码虽能读取新增可空列，仍缺少新准入规则；不要为了回退UI直接恢复旧鉴权业务。保留字段、索引与Flyway历史，优先发布兼容修正。

最终已部署 `uat-20261031-user-identity-03`，健康UP、Flyway V8、TLS校验通过。68项普通后端、9项验收HTTP、109项TypeScript测试及H5/小程序构建通过；补充覆盖并发密码重置时旧密码登录不能签发新会话。正式短信、权威证件验真和小程序真机仍未接入/验收。
