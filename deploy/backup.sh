#!/usr/bin/env bash
# Dumps the catalog's database to a file and keeps the last N days of dumps.
#
# Run nightly from the host's crontab (see deploy/README.md, "Backups"). The dump is
# pg_dump's custom format: compressed, and restorable table by table with pg_restore.
#
#   PRETIUM_BACKUP_DIR        where dumps go            (default /opt/pretium/backups)
#   PRETIUM_BACKUP_KEEP_DAYS  how many days to keep     (default 7)
#   PRETIUM_COMPOSE_FILE      the stack's compose file  (default: the one next to this script)
#
# The database is a view that the checks derive from the raw evidence, so this dump is the
# quick way back, not the only one: docs/RUNBOOK.md, "Losing data".
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
compose_file="${PRETIUM_COMPOSE_FILE:-$here/docker-compose.yml}"
backups="${PRETIUM_BACKUP_DIR:-/opt/pretium/backups}"
keep_days="${PRETIUM_BACKUP_KEEP_DAYS:-7}"

mkdir -p "$backups"
stamp="$(date -u +%Y-%m-%dT%H%MZ)"
out="$backups/pretium-$stamp.dump"

# -T: no pseudo-terminal, so the dump's bytes reach the file untouched.
docker compose -f "$compose_file" exec -T postgres \
  pg_dump -U pretium -Fc --no-owner pretium > "$out.part"
mv "$out.part" "$out"

find "$backups" -name 'pretium-*.dump' -mtime +"$keep_days" -delete
echo "$(date -u +%FT%TZ) wrote $out ($(du -h "$out" | cut -f1));" \
  "$(find "$backups" -name 'pretium-*.dump' | wc -l) dumps kept"
