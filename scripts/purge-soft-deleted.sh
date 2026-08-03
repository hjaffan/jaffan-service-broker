#!/usr/bin/env bash
#
# Admin purge for soft-deleted tenants. When DEPROVISION_MODE=soft, deprovision parks a tenant as
# `deleted_<original>_<epochMillis>` instead of dropping it. This script HARD-DROPS every parked
# database whose embedded timestamp is older than N days, on one backend server.
#
# The age is read straight from the database name (the trailing _<epochMillis>), so this needs no
# broker state — matching the broker's own stateless design.
#
# Usage:
#   ./scripts/purge-soft-deleted.sh --engine postgres --host H --port 5432 --user U --days 7 [--apply]
#   ./scripts/purge-soft-deleted.sh --engine mariadb  --host H --port 3306 --user U --days 7 [--apply]
#
# Password is read from PGPASSWORD (postgres) or MYSQL_PWD (mariadb) to keep it off the command line.
# Without --apply the script is a DRY RUN and only prints what it would drop.
set -euo pipefail

ENGINE="" HOST="" PORT="" USER="" DAYS="7" APPLY="false"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --engine) ENGINE="$2"; shift 2;;
    --host)   HOST="$2";   shift 2;;
    --port)   PORT="$2";   shift 2;;
    --user)   USER="$2";   shift 2;;
    --days)   DAYS="$2";   shift 2;;
    --apply)  APPLY="true"; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
[[ -n "$ENGINE" && -n "$HOST" && -n "$PORT" && -n "$USER" ]] || {
  echo "required: --engine --host --port --user (see header for usage)" >&2; exit 2; }

now_ms="$(( $(date +%s) * 1000 ))"
cutoff_ms="$(( now_ms - DAYS * 86400 * 1000 ))"
echo "Purging parked databases older than ${DAYS} day(s) on ${ENGINE} ${HOST}:${PORT} (apply=${APPLY})"

list_dbs() {
  case "$ENGINE" in
    postgres)
      psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -Atqc \
        "SELECT datname FROM pg_database WHERE datname LIKE 'deleted\_%';" ;;
    mariadb)
      "$(command -v mariadb || command -v mysql)" -h "$HOST" -P "$PORT" -u "$USER" -N -B -e \
        "SELECT schema_name FROM information_schema.SCHEMATA WHERE schema_name LIKE 'deleted\_%';" ;;
    *) echo "unknown engine: $ENGINE" >&2; exit 2;;
  esac
}

drop_db() {
  local db="$1"
  case "$ENGINE" in
    postgres) psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -v ON_ERROR_STOP=1 \
                 -c "DROP DATABASE IF EXISTS \"${db}\";" ;;
    mariadb)  "$(command -v mariadb || command -v mysql)" -h "$HOST" -P "$PORT" -u "$USER" -e \
                 "DROP DATABASE IF EXISTS \`${db}\`;" ;;
  esac
}

while IFS= read -r db; do
  [[ -z "$db" ]] && continue
  epoch="${db##*_}"                        # trailing _<epochMillis>
  if ! [[ "$epoch" =~ ^[0-9]+$ ]]; then
    echo "  skip (no epoch suffix): ${db}"; continue
  fi
  if (( epoch < cutoff_ms )); then
    if [[ "$APPLY" == "true" ]]; then
      echo "  DROP ${db}"; drop_db "$db"
    else
      echo "  would drop ${db} (parked $(( (now_ms - epoch) / 86400000 )) day(s) ago)"
    fi
  else
    echo "  keep ${db} (younger than ${DAYS} day(s))"
  fi
done < <(list_dbs)

echo "Done."
