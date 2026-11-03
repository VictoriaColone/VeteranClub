#!/usr/bin/env bash
# Run once as the server administrator, after reviewing the deployment grant.
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || { echo 'Run this installer with sudo.' >&2; exit 1; }
public_key=${1:?Specify the approved computer deployment public key}
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f /etc/systemd/system/norman-acceptance.service.d/20-rds.conf ]] || exit 1
[[ -L /opt/norman-acceptance/current && -d /opt/norman-acceptance/releases ]] || exit 1
[[ -x /usr/bin/python3 && -x /usr/sbin/sshd && -x /usr/sbin/visudo ]] || exit 1
ssh-keygen -lf "$public_key"
[[ $(wc -l < "$public_key") -eq 1 ]] || exit 1
read -r key_type key_body rest < "$public_key"
[[ $key_type == ssh-ed25519 && $key_body =~ ^[A-Za-z0-9+/=]+$ ]] || exit 1
if id norman-deploy >/dev/null 2>&1; then
  [[ -f /etc/norman-deploy-managed ]] || { echo 'Refusing to replace an unmanaged existing account.' >&2; exit 1; }
else
  useradd --system --home-dir /var/lib/norman-deploy-login --shell /bin/sh norman-deploy
  touch /etc/norman-deploy-managed
fi
install -d -m 755 -o root -g root /var/lib/norman-deploy-login /usr/local/lib/norman-deploy /etc/ssh/authorized_keys
install -d -m 700 -o root -g root /var/lib/norman-deploy /var/lib/norman-deploy/bundles
install -m 755 -o root -g root "$source_dir/ssh-deploy-worker.py" /usr/local/lib/norman-deploy/worker.py
cat > /usr/local/lib/norman-deploy/ssh-entry <<'ENTRY'
#!/bin/sh
exec /usr/bin/sudo -n /usr/local/lib/norman-deploy/worker.py "${SSH_ORIGINAL_COMMAND-}"
ENTRY
chmod 755 /usr/local/lib/norman-deploy/ssh-entry
printf 'restrict %s %s norman-computer-deploy\n' "$key_type" "$key_body" > /etc/ssh/authorized_keys/norman-deploy
chmod 644 /etc/ssh/authorized_keys/norman-deploy
sudoers=$(mktemp)
trap 'rm -f "$sudoers"' EXIT
printf 'norman-deploy ALL=(root) NOPASSWD: /usr/local/lib/norman-deploy/worker.py\n' > "$sudoers"
/usr/sbin/visudo -cf "$sudoers"
install -m 440 -o root -g root "$sudoers" /etc/sudoers.d/norman-deploy
/usr/sbin/visudo -c
backup="/etc/ssh/sshd_config.norman-deploy-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p /etc/ssh/sshd_config "$backup"
if ! grep -q '^# NORMAN COMPUTER DEPLOY ACCESS$' /etc/ssh/sshd_config; then
cat >> /etc/ssh/sshd_config <<'SSHD'

# NORMAN COMPUTER DEPLOY ACCESS
Match User norman-deploy
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AuthorizedKeysFile /etc/ssh/authorized_keys/norman-deploy
    AllowTcpForwarding no
    AllowStreamLocalForwarding no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTunnel no
    PermitTTY no
    ForceCommand /usr/local/lib/norman-deploy/ssh-entry
SSHD
fi
if ! /usr/sbin/sshd -t; then cp -p "$backup" /etc/ssh/sshd_config; exit 1; fi
effective=$(/usr/sbin/sshd -T -C user=norman-deploy,host=localhost,addr=127.0.0.1)
for expected in 'forcecommand /usr/local/lib/norman-deploy/ssh-entry' 'authenticationmethods publickey' 'passwordauthentication no' 'permittty no' 'allowtcpforwarding no' 'allowagentforwarding no' 'x11forwarding no'; do
  if ! grep -qx "$expected" <<< "$effective"; then
    cp -p "$backup" /etc/ssh/sshd_config
    echo 'A conflicting SSH policy blocks the restricted deployment configuration.' >&2
    exit 1
  fi
done
systemctl reload ssh
echo 'Computer deployment access installed: project upload, deployment and health only.'
echo 'Existing preview account and phone keys were not changed.'
