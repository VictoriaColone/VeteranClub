#!/usr/bin/env bash
# Update a private acceptance environment that has already moved to RDS.
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || exit 1
bundle=${1:?Specify the release archive}
release=${2:?Specify a new release identifier}
manager_hash=${3:-}
[[ $release =~ ^[A-Za-z0-9_-]{7,64}$ ]] || exit 1
[[ -f /etc/systemd/system/norman-acceptance.service.d/20-rds.conf ]] || exit 1
base=/opt/norman-acceptance
previous=$(readlink -f "$base/current")
[[ ! -e "$base/releases/$release" ]] || exit 1
mkdir "$base/releases/$release"
tar --extract --gzip --file "$bundle" --directory "$base/releases/$release" --no-same-owner
[[ -f "$base/releases/$release/norman-acceptance.jar" && -f "$base/releases/$release/web/mobile/index.html" && -f "$base/releases/$release/web/admin/index.html" ]] || exit 1
chmod -R u=rwX,go=rX "$base/releases/$release"
if [[ -n $manager_hash ]]; then
  bash "$base/releases/$release/deploy/rds/enable-manager.sh" "$manager_hash"
fi
ln -s "$base/releases/$release" "$base/current-$release-next"
mv -Tf "$base/current-$release-next" "$base/current"
systemctl restart norman-acceptance
ready=false
for attempt in {1..45}; do
  if curl --fail --silent --output /dev/null http://127.0.0.1:8091/actuator/health; then ready=true; break; fi
  sleep 1
done
if [[ $ready != true ]]; then
  ln -sfn "$previous" "$base/current"
  systemctl restart norman-acceptance
  echo 'New API failed its health check; restored previous application release.' >&2
  exit 1
fi
echo "RDS acceptance application updated: $release"
