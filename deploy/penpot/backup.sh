#!/usr/bin/env bash
#
# Backs up Penpot: the database (pg_dump) and the assets (images, fonts, files).
# Run from this folder (next to docker-compose.yml), e.g. daily from cron:
#   15 2 * * * cd /opt/penpot && ./backup.sh >> backups/backup.log 2>&1
#
# Restore: see README.md, "Restore".

set -euo pipefail

cd "$(dirname "$0")"
KEEP_DAYS=${KEEP_DAYS:-14}
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p backups

echo "[$(date -Is)] database → backups/penpot-db-$STAMP.sql.gz"
docker compose exec -T penpot-postgres pg_dump -U penpot -d penpot --no-owner \
  | gzip > "backups/penpot-db-$STAMP.sql.gz"

echo "[$(date -Is)] assets → backups/penpot-assets-$STAMP.tar.gz"
# compressed on the host: the penpot-backend image ships tar but not gzip
docker compose exec -T penpot-backend tar -cf - -C /opt/data assets \
  | gzip > "backups/penpot-assets-$STAMP.tar.gz"

# keep the last KEEP_DAYS days
find backups -name 'penpot-*' -type f -mtime +"$KEEP_DAYS" -delete

echo "[$(date -Is)] done"
ls -lh "backups/penpot-db-$STAMP.sql.gz" "backups/penpot-assets-$STAMP.tar.gz"
