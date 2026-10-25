#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ ${EUID} -eq 0 ]] || exit 1
directory=/var/backups/norman-acceptance
mkdir -p "$directory"
file="$directory/norman_acceptance-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
# The container expands its own MYSQL_PASSWORD; it never appears in host arguments/output.
docker exec norman-acceptance-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --single-transaction --no-tablespaces --user="$MYSQL_USER" norman_acceptance' | gzip > "$file"
echo "$file"
