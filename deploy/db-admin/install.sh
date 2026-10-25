#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
adminer_file=${1:?Specify the official Adminer 6.2.0 MySQL PHP file}
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
version=6.2.0
checksum=7492bf2b158fee5b7ddc9e13255322c4601b1f51b797896e303882626bf1e5af
printf '%s  %s\n' "$checksum" "$adminer_file" | sha256sum --check --status
command -v php-fpm8.3 >/dev/null
command -v nginx >/dev/null
site=/etc/nginx/sites-available/norman-acceptance
[[ -f $site ]] || { echo 'Install the private acceptance site first.' >&2; exit 1; }
grep -Eq 'listen[[:space:]]+127\.0\.0\.1:8088;' "$site"
if ! id norman-db-admin >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin norman-db-admin
fi
install -d -m 755 /opt/norman-db-admin /opt/norman-db-admin/releases "/opt/norman-db-admin/releases/$version" /etc/nginx/norman-acceptance-extra
install -d -m 700 -o norman-db-admin -g norman-db-admin /var/lib/norman-db-admin /var/lib/norman-db-admin/sessions /var/lib/norman-db-admin/uploads /var/log/norman-db-admin
install -m 644 "$adminer_file" "/opt/norman-db-admin/releases/$version/adminer.php"
ln -sfn "/opt/norman-db-admin/releases/$version" /opt/norman-db-admin/current
install -m 644 "$script_dir/php-pool.conf" /etc/php/8.3/fpm/pool.d/norman-db-admin.conf
php-fpm8.3 --test
install -m 644 "$script_dir/nginx-location.conf" /etc/nginx/norman-acceptance-extra/database.conf
backup="$site.db-admin-backup-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$site" "$backup"
python3 - "$site" <<'PY'
from pathlib import Path
import sys
file=Path(sys.argv[1]); content=file.read_text()
include='    include /etc/nginx/norman-acceptance-extra/*.conf;'
if include not in content:
    needle='    index index.html;'
    assert content.count(needle)==1, 'Cannot safely identify the acceptance server block'
    file.write_text(content.replace(needle,needle+'\n'+include,1))
PY
if ! nginx -t; then cp -p "$backup" "$site"; exit 1; fi
systemctl enable --now php8.3-fpm
systemctl reload php8.3-fpm
systemctl reload nginx
# Adminer intentionally returns 403 for its unauthenticated login page.
login_page=$(mktemp)
trap 'unlink "$login_page"' EXIT
status=$(curl --silent --show-error --output "$login_page" --write-out '%{http_code}' 'http://127.0.0.1:8088/database/?server=127.0.0.1%3A3308&username=norman_app&db=norman_acceptance')
[[ $status == 403 || $status == 200 ]]
grep -Fq 'auth[password]' "$login_page"
echo 'Adminer 6.2.0 ready at /database/ on the existing private acceptance tunnel; database authentication is required.'
