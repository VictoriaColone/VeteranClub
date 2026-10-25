#!/usr/bin/env bash
# Run only after the server owner explicitly approves this restricted SSH access.
set -euo pipefail
[[ ${EUID} -eq 0 ]] || exit 1
public_key=${1:?Specify the approved SSH public key file}
ssh-keygen -lf "$public_key"
read -r key_type key_body rest < "$public_key"
[[ $key_type == ssh-ed25519 && $key_body =~ ^[A-Za-z0-9+/=]+$ ]] || exit 1
if ! id norman-preview >/dev/null 2>&1; then
  useradd --system --create-home --home-dir /var/lib/norman-preview --shell /usr/sbin/nologin norman-preview
fi
install -d -m 700 -o norman-preview -g norman-preview /var/lib/norman-preview/.ssh
printf 'restrict,port-forwarding,permitopen="127.0.0.1:8088",command="/usr/bin/false" %s %s norman-acceptance-tunnel\n' "$key_type" "$key_body" > /var/lib/norman-preview/.ssh/authorized_keys
chown norman-preview:norman-preview /var/lib/norman-preview/.ssh/authorized_keys
chmod 600 /var/lib/norman-preview/.ssh/authorized_keys
backup="/etc/ssh/sshd_config.norman-backup-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p /etc/ssh/sshd_config "$backup"
if ! grep -q '^# NORMAN ACCEPTANCE TUNNEL$' /etc/ssh/sshd_config; then
cat >> /etc/ssh/sshd_config <<'SSHD'

# NORMAN ACCEPTANCE TUNNEL
Match User norman-preview
    AuthenticationMethods publickey
    PasswordAuthentication no
    PubkeyAuthentication yes
    AllowTcpForwarding local
    PermitOpen 127.0.0.1:8088
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    ForceCommand /usr/bin/false
SSHD
fi
if ! /usr/sbin/sshd -t; then cp -p "$backup" /etc/ssh/sshd_config; exit 1; fi
systemctl reload ssh
/usr/sbin/sshd -T -C user=norman-preview,host=localhost,addr=127.0.0.1 | grep -E '^(allowtcpforwarding|permitopen|forcecommand|permittty|passwordauthentication) '
echo 'Restricted tunnel account ready; no shell or sudo access.'
