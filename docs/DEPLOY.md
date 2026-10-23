# 单机静态部署

运行 `npm ci && npm run build` 后，将整个 `dist/` 目录复制至服务器 `/var/www/stellar-club/`。现阶段只有静态前端和浏览器模拟服务，无需购买数据库或部署 Java 应用。

Nginx 示例（域名和证书替换为自己的配置）：

```nginx
server {
    listen 80;
    server_name club.example.com;
    return 301 https://$host$request_uri;
}
server {
    listen 443 ssl;
    server_name club.example.com;
    ssl_certificate /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    root /var/www/stellar-club;
    index index.html;
    add_header X-Content-Type-Options nosniff;
    add_header Referrer-Policy strict-origin-when-cross-origin;
    location = /admin { return 302 /admin/; }
    location = /mobile { return 302 /mobile/; }
    location /admin/ { try_files $uri $uri/ /admin/index.html; }
    location /mobile/ { try_files $uri $uri/ /mobile/index.html; }
    location / { try_files $uri $uri/ /index.html; }
    # 真实后端接入时启用，并切换前端的 API 模式：
    # location /api/ {
    #     proxy_pass http://127.0.0.1:8080/;
    #     proxy_set_header Host $host;
    #     proxy_set_header X-Real-IP $remote_addr;
    # }
}
```

H5 使用 Hash 路由，移动端入口 `/mobile/#/pages/index/index`。统一主域名下的两个路径可共享模拟数据。生产后台的真实权限必须由后端校验，不能依赖页面隐藏或 LocalStorage。

当前网站是私有交互演示，微信授权、收付款、员工登录均未连接真实服务。不要录入真实身份资料或用于实际交易。
