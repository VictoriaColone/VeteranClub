#!/usr/bin/env bash
set -euo pipefail
umask 077
project_root="$(cd "$(dirname "$0")/.." && pwd)"
backup_dir="${BACKUP_DIR:-$project_root/.local/backups}"
mkdir -p "$backup_dir"
backup_file="$backup_dir/norman-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
trap 'rm -f "$backup_file.partial"' EXIT
docker compose --env-file "$project_root/deploy/.env" -f "$project_root/deploy/compose.yml" exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --set-gtid-purged=OFF norman_club' \
  | gzip > "$backup_file.partial"
gzip -t "$backup_file.partial"
mv "$backup_file.partial" "$backup_file"
printf 'Backup ready: %s\n' "$backup_file"
# Encrypt and upload this file to the organization's private off-server backup storage.
