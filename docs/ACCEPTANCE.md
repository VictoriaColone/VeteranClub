# 服务器内部验收

尚未完成域名实名、备案和短信／商户资料时，可先部署独立验收环境。当前采用服务器本机监听＋SSH 隧道；不开放网站或数据库的公网端口，不修改 DNS。阿里云对中国内地网站正式对外服务的要求见[使用须知](https://help.aliyun.com/zh/simple-application-server/product-overview/usage-notes)。

验收环境用于检查页面、API、数据库和后台任务的完整链路。**所有账号和收付款为验收样例**，数据写入服务器 `norman_acceptance` 数据库，用户端和员工后台共用；与浏览器 Mock、开发库 `norman_club` 及未来生产库相互独立。不要录入真实客户资料。

## 打包与隔离

```bash
npm run server:build:acceptance
npm run build:acceptance
```

- 服务端验收 JAR：`server/target/acceptance/norman-acceptance.jar`。
- 前端验收产物：`dist-acceptance/`。
- 普通 `npm run server:build` 不包含 `src/acceptance/` 的任何身份切换或模拟付款入口；验收编译目录也单独隔离，防止残留 class 混入正式 JAR。
- 验收包启动要求：显式 `ACCEPTANCE_MODE=true`、开发模式、仅监听 `127.0.0.1`、指定的本机来源、独立 `norman_acceptance` 数据库、真实短信关闭。
- 验收会话仍使用 HttpOnly Cookie、CSRF、角色权限与业务事务。页面自动获得预设测试身份，可以切换用户和员工角色。
- 微信、支付宝仍是未配置状态；模拟付款使用单独的 `acceptance` 渠道，不能接收外部支付通知。退款由验收渠道模拟确认；确认完成后只自动记账，真实转账仍待接入。
- 组队订单的“模拟走满时长”只存在于验收包；正式包按服务器时钟履约。
- 页面顶部明确显示“服务器验收／内部验收、模拟收付款”，数据不会保存在浏览器业务数据库中。

预设用户：林序（无资格）、小屿（仅组队）、凛（仅护航）、岚（双资格）、远山（双资格，可作为副手）。可以用林序提交入驻申请，在后台客户管理审核、安排考核并录入成绩；资格变化写入真实数据库。

## 服务器结构

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
