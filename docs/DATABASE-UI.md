# 数据库可视化管理

现有云服务器上的 MySQL 可以通过免费的 [Adminer](https://www.adminer.org/) 网页管理，无须购买 RDS。当前采用 Adminer 6.2.0 的官方 MySQL 多语言版本，保留数据库密码登录。

## 打开与登录

先在本机项目目录运行 `npm run server:tunnel`，保持隧道连接；打开：

<http://127.0.0.1:4190/database/?server=127.0.0.1%3A3308&username=norman_app&db=norman_acceptance&lang=zh>

连接参数已经填好：

| 项目 | 值 |
|---|---|
| 系统 | MySQL / MariaDB |
| 服务器 | `127.0.0.1:3308`（云服务器内部地址） |
| 用户名 | `norman_app` |
| 数据库 | `norman_acceptance` |
| 密码 | 云服务器 `/etc/norman-acceptance/mysql.env` 中的 `MYSQL_PASSWORD` |

数据库密码由首次部署生成，网页和源码中没有内置密码。所有者可在阿里云 Workbench 终端自行查看上述配置文件，复制 `MYSQL_PASSWORD=` 后的值到登录页；不必填写 `MYSQL_ROOT_PASSWORD`。

登录后可点表名查看字段、索引及关系，点“选择数据”浏览、排序、筛选记录；也可使用 SQL 查询、数据编辑及导入导出。订单状态、支付和结算操作应通过业务后台完成，直接编辑数据表不会触发完整业务流程。

常用表：`club_user`（用户）、`product`（商品）、`club_order`（订单）、`order_member`（负责人及副手）、`qualification_application`（资格申请）、`service_qualification`（已有资格）、`income_entry`（收入记账）、`refund_request`（退款申请）。金额以分存储。

## 安装与维护

在云服务器通过 Ubuntu 官方软件源安装 PHP-FPM 8.3、MySQL 和多字节字符串扩展：

```bash
sudo apt-get install -y --no-install-recommends php8.3-fpm php8.3-mysql php8.3-mbstring
```

从 Adminer 官网提供的 [6.2.0 MySQL 下载链接](https://github.com/vrana/adminer/releases/download/v6.2.0/adminer-6.2.0-mysql.php) 下载 PHP 文件，将 `deploy/db-admin/` 目录上传到服务器，再运行：

```bash
sudo bash /home/admin/db-admin/install.sh /home/admin/adminer-6.2.0-mysql.php
sudo python3 /home/admin/db-admin/check.py
```

安装器先校验 PHP 文件 SHA-256，使用独立无登录系统用户、按需启动的 PHP-FPM 池，最多 2 个工作进程、每个 PHP 请求 64MiB 内存上限。PHP 只通过 Unix socket 接收 Nginx 请求；管理页面随原有 `127.0.0.1:8088` 验收站点访问，不增加公网端口，也不扩大受限 SSH 账号可转发的目标。

沿用已有数据库账号，不创建数据库用户、不重置密码，安装脚本不把密码写入源码或前端配置。数据库连接信息与登录会话由 Adminer 正常处理，PHP 会话临时保存在 `/var/lib/norman-db-admin/sessions`，不是免密登录。未登录时 Adminer 的登录页会返回 HTTP 403；应同时检查页面是否展示登录表单，不能只按状态码判断部署失败。

| 内容 | 路径 |
|---|---|
| 工具代码 | `/opt/norman-db-admin/current/adminer.php` |
| PHP 池 | `/etc/php/8.3/fpm/pool.d/norman-db-admin.conf` |
| Unix socket | `/run/php/norman-db-admin.sock` |
| Nginx 路由 | `/etc/nginx/norman-acceptance-extra/database.conf` |
| PHP 日志 | `/var/log/norman-db-admin/php.log` |

上传 SQL 文件大小上限 4MiB，请求体上限 8MiB；更大的导入继续通过服务器终端处理。更新 Adminer 时需要从官方来源获取新版本，并核对、更新安装器中的版本与文件校验值。
