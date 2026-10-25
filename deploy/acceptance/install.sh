#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || { echo 'Run this installer with sudo.' >&2; exit 1; }
if [[ -f /etc/systemd/system/norman-acceptance.service.d/20-rds.conf ]]; then
  echo 'RDS cutover is installed. Use deploy/rds/update.sh; the local-DB installer will not recreate MySQL.' >&2
  exit 1
fi
bundle=${1:?Specify the uploaded acceptance archive}
release=${2:?Specify the release identifier}
[[ $release =~ ^[A-Za-z0-9_-]{7,64}$ ]] || exit 1
mysql_image=${MYSQL_ACCEPTANCE_IMAGE:-public.ecr.aws/docker/library/mysql:8.4}
case "$mysql_image" in
  public.ecr.aws/docker/library/mysql:8.4|container-registry.oracle.com/mysql/community-server:8.4) ;;
  *) echo 'Use a supported official MySQL image.' >&2; exit 1 ;;
esac
command -v docker >/dev/null
docker image inspect "$mysql_image" >/dev/null
command -v java >/dev/null
command -v nginx >/dev/null
base=/opt/norman-acceptance
mkdir -p "$base/releases" /etc/norman-acceptance /var/log/norman-acceptance /var/lib/norman-acceptance/mysql
chmod 755 "$base" "$base/releases"
if ! id norman-app >/dev/null 2>&1; then useradd --system --no-create-home --shell /usr/sbin/nologin norman-app; fi
if [[ -e "$base/releases/$release" ]]; then echo 'This release already exists; use a new identifier.' >&2; exit 1; fi
mkdir "$base/releases/$release"
tar --extract --gzip --file "$bundle" --directory "$base/releases/$release" --no-same-owner
chmod -R u=rwX,go=rX "$base/releases/$release"
python3 - <<'PY'
import base64,secrets,os
from pathlib import Path
folder=Path('/etc/norman-acceptance')
database=folder/'mysql.env'; api=folder/'api.env'
if not database.exists():
    database.write_text('MYSQL_ROOT_PASSWORD='+secrets.token_hex(32)+'\nMYSQL_DATABASE=norman_acceptance\nMYSQL_USER=norman_app\nMYSQL_PASSWORD='+secrets.token_hex(32)+'\nTZ=Asia/Shanghai\n')
if not api.exists():
    db=dict(line.split('=',1) for line in database.read_text().splitlines())
    settings={
      'ACCEPTANCE_MODE':'true','CLUB_DEVELOPMENT':'true','COOKIE_SECURE':'false',
      'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'8091',
      'DB_URL':'jdbc:mysql://127.0.0.1:3308/norman_acceptance?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&sslMode=REQUIRED',
      'DB_USERNAME':db['MYSQL_USER'],'DB_PASSWORD':db['MYSQL_PASSWORD'],
      'CLUB_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),
      'CLUB_HASH_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),
      'ALLOWED_ORIGINS':'http://127.0.0.1:4190,http://localhost:4190',
      'TRUSTED_PROXIES':'127.0.0.1','SMS_PROVIDER':'disabled',
      'LOG_FILE':'/var/log/norman-acceptance/server.log'}
    api.write_text(''.join(k+'='+v+'\n' for k,v in settings.items()))
for file in (database,api): os.chmod(file,0o600)
PY
if ! docker container inspect norman-acceptance-mysql >/dev/null 2>&1; then
  docker run --detach --name norman-acceptance-mysql --restart unless-stopped \
    --memory=512m --memory-swap=512m --cpus=0.8 --publish 127.0.0.1:3308:3306 \
    --env-file /etc/norman-acceptance/mysql.env \
    --volume /var/lib/norman-acceptance/mysql:/var/lib/mysql \
    --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    "$mysql_image" --innodb-buffer-pool-size=96M --max-connections=20 --mysqlx=0 --slow-query-log=OFF --performance-schema=OFF
else
  docker update --memory=512m --memory-swap=512m norman-acceptance-mysql >/dev/null
  docker start norman-acceptance-mysql >/dev/null
fi
ready=false
for attempt in {1..120}; do
  if docker exec norman-acceptance-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" "$MYSQL_DATABASE" --execute="SELECT 1"' >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ $ready == true ]] || { echo 'MySQL did not become ready.' >&2; exit 1; }
chown norman-app:norman-app /var/log/norman-acceptance
ln -sfn "$base/releases/$release" "$base/current"
install -m 644 "$base/current/deploy/norman-acceptance.service" /etc/systemd/system/norman-acceptance.service
install -m 644 "$base/current/deploy/nginx.conf" /etc/nginx/sites-available/norman-acceptance
ln -sfn /etc/nginx/sites-available/norman-acceptance /etc/nginx/sites-enabled/norman-acceptance
nginx -t
systemctl daemon-reload
systemctl enable --now norman-acceptance.service
systemctl restart norman-acceptance.service
systemctl reload nginx
ready=false
for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:8091/actuator/health >/dev/null; then ready=true; break; fi
  sleep 2
done
[[ $ready == true ]] || { echo 'API did not become ready; check the service log.' >&2; exit 1; }
curl --fail --silent --output /dev/null http://127.0.0.1:8088/mobile/
curl --fail --silent --output /dev/null http://127.0.0.1:8088/admin/
echo "Acceptance deployment ready: release=$release, web=127.0.0.1:8088, mysql=127.0.0.1:3308"
