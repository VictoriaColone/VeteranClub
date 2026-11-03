#!/bin/bash
# Official Homebrew WireGuard client. The device owner enters sudo locally.
set -euo pipefail
VPN_ACTION=${1:-up}
VPN_CONFIG=${2:?Specify the local device .conf file}
case "$VPN_ACTION" in up|down) ;; *) echo 'Use up or down.' >&2; exit 1;; esac
[[ -f "$VPN_CONFIG" && "$VPN_CONFIG" = /* ]] || { echo 'Local VPN configuration is missing.' >&2; exit 1; }
VPN_TOOL=
for VPN_CANDIDATE in /opt/homebrew/bin/wg-quick /usr/local/bin/wg-quick; do
    if [[ -x "$VPN_CANDIDATE" ]]; then VPN_TOOL=$VPN_CANDIDATE; break; fi
done
[[ -n "$VPN_TOOL" ]] || { echo 'Install the official wireguard-tools Homebrew package first.' >&2; exit 1; }
echo '诺曼部落员工 VPN：只路由 10.77.42.1，其他上网流量保持原状。'
echo '如提示 Password，请在本机终端输入 Mac 登录密码；输入时不显示字符。'
sudo /usr/bin/env PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin "$VPN_TOOL" "$VPN_ACTION" "$VPN_CONFIG"
if [[ "$VPN_ACTION" == up ]]; then
    echo 'VPN 已启动。后台地址：https://admin.veteranclub.icu:9443/admin/'
else
    echo 'VPN 已断开。'
fi
