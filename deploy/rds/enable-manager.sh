#!/usr/bin/env bash
# Configure a first-time password hash for the existing private RDS acceptance service.
# Does not reset an existing database account or change database credentials.
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || exit 1
source_hash=${1:?Provide the private BCrypt cost=12 hash file}
[[ -f $source_hash && ! -L $source_hash ]] || exit 1
python3 - "$source_hash" <<'PY'
from pathlib import Path
import re
import sys
p = Path(sys.argv[1])
if p.stat().st_size > 100 or not re.fullmatch(r'\$2[aby]\$12\$[./A-Za-z0-9]{53}', p.read_text().strip()):
    raise SystemExit('Invalid BCrypt hash file')
PY
install -d -o root -g norman-app -m 0710 /etc/norman-manager
target=/etc/norman-manager/password.hash
if [[ -e $target ]]; then
  cmp --silent "$source_hash" "$target" || { echo 'Existing manager hash retained; use the offline reset command to change a password.' >&2; exit 1; }
else
  install -o norman-app -g norman-app -m 0400 "$source_hash" "$target"
fi
runuser -u norman-app -- test -r "$target"
install -d -m 0755 /etc/systemd/system/norman-acceptance.service.d
cat > /etc/systemd/system/norman-acceptance.service.d/30-manager.conf <<'CONF'
[Service]
Environment=CLUB_MANAGER_PASSWORD_HASH_FILE=/etc/norman-manager/password.hash
CONF
systemctl daemon-reload
echo 'Private manager bootstrap configured; deploy the updated application to activate it.'
