# 私有验收环境迁移至 RDS

本流程用于将已有 `norman_acceptance` 数据库迁出应用服务器。网站继续仅监听本机，通过 SSH 隧道访问；不开放 RDS 公网地址。正式环境仍使用正常认证和独立生产库。

## 云资源准备

- 原轻量服务器原地升级至 2 核 4GB、50GB，并检查分区和文件系统已扩容。
- 同地域 RDS MySQL 8.4 基础版，2 核 4GB、100GB；基础版是单节点，不提供高可用切换保障。暂不购买 Redis。
- 轻量服务器与 RDS VPC 内网互通。RDS 白名单仅添加应用服务器的私网 IP `/32`，不添加全 VPC 或公网网段。
- 创建 `norman_acceptance`、字符集 `utf8mb4`；创建普通账号 `norman_service`，仅授权该库读写（包含 Flyway 所需的 DDL）。管理账号不供应用使用。
- 对实际内网域名开启 SSL 和强制加密，从阿里云下载 CA 证书。将 PEM 和 JKS 分别安装到 `/etc/norman-rds-ca/rds-ca.pem`、`rds-ca.jks`，目录 755、文件 644。这些是公开 CA 证书，不是数据库密钥。

参考[阿里云 SSL 配置文档](https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/configure-a-cloud-certificate-to-enable-ssl-encryption/)。官方 JKS 信任库密码为 `apsaradb`；应用验证证书和域名，不关闭证书校验。

## 迁移步骤

先创建系统盘快照。将新验收 JAR、前端及 `deploy/acceptance/` 打包，使用原安装器发布支持 RDS 的版本，并包含新的维护模式 Nginx 配置。另将 `deploy/rds/` 工具放到服务器。

以下命令在云服务器上执行；实际目录按上传位置调整。

```bash
# 不需要 RDS 密码，先对现有库导出并校验完整 gzip。
sudo python3 /home/admin/rds/migrate.py backup-source

# 本人交互输入已创建的 RDS 账号密码，密码隐藏、不进入 shell 历史。
sudo python3 /home/admin/rds/configure.py

# 检查私网 DNS、TLS、指定数据库和空目标；不会覆盖已有表。
sudo python3 /home/admin/rds/migrate.py check
sudo python3 /home/admin/rds/migrate.py migrate
```

`rds.env` 仅保存在云服务器 `/etc/norman-acceptance/`，root 所有、权限 600。不要把密码发到聊天、写入命令行或放入公开 Git 仓库。API 的其他密钥和会话配置沿用原值。

迁移脚本开启维护页并停止 API，再生成最新备份、导入 RDS，逐表比对行数和完整内容 SHA-256。仅全部一致才切换连接并重启 API；失败时恢复原应用连接。备份、原配置和校验清单位于 `/var/backups/norman-acceptance/rds-cutover-*/`。部分导入失败时目标库会被保留供检查，脚本不会自动删表或重试覆盖。

成功后 API 的 JVM 堆上限为 512MiB、进程内存上限 1200MiB，并解除 Docker 服务依赖。后续应用发布用 `deploy/rds/update.sh`；原本地库安装器会拒绝在已迁移环境运行，避免重新创建 MySQL。

## 验证与清理

- 检查 API 健康、两端订单、资格和记账数据，确认用户端与管理端读取同一 RDS 库。
- `sudo python3 /home/admin/rds/capacity-check.py --output /var/backups/norman-acceptance/capacity.json` 模拟 100 个按 30 秒间隔访问首页的在线访客。使用 4 个测试身份，包含 30 秒渐进加入，不修改业务订单；不是 100 笔同时写入压测。报告仅输出请求量、错误和延迟，不输出会话或响应数据。
- 前端按当前页面请求所需资源：接单/订单页约 10 秒，其他页面及后台约 30 秒，带随机错峰，隐藏页面暂停；游戏目录和平台规则缓存 5 分钟。操作后的刷新仍立即执行。
- 迁移脚本不会删除旧数据库。所有数据校验和业务验收通过后，停止旧容器并关闭自动重启，保留已验证备份和快照，再移除旧数据库及不再使用的 Adminer 组件。
- RDS 自带备份需在控制台确认开启和保留策略；本地导出不替代 RDS 备份。配置加密密钥也需单独安全保管。

本地验证：`npm test`、`npm run check`、`npm run server:build:acceptance`、`npm run build:acceptance`、`npm run build:mp-weixin`，以及 `python3 -B -m unittest discover -s tests -p test_rds_migration.py`。
