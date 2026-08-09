#!/usr/bin/env bash
#
# Operator purge for retired tenants. The broker itself NEVER drops a database: deprovision renames
# the tenant to `retired_<original>_<epochMillis>` and blocks connections to it. This script is the
# deliberate, out-of-band way to reclaim space: it HARD-DROPS every retired database whose embedded
# timestamp is older than N days, on the postgres-ha cluster.
#
# The age is read straight from the database name (the trailing _<epochMillis>), so this needs no
# broker state — matching the broker's own stateless design.
#
# To RECOVER a retired database instead of purging it:
#   ALTER DATABASE "retired_si_<guid>_<epoch>" WITH ALLOW_CONNECTIONS true;
#   ALTER DATABASE "retired_si_<guid>_<epoch>" RENAME TO "si_<guid>";   -- if re-attaching to CF
#
# Usage:
#   PGPASSWORD=... ./scripts/purge-retired.sh --host H --port 5432 --user U --days 30 [--apply]
#
# Password is read from PGPASSWORD to keep it off the command line. Without --apply the script is a
# DRY RUN and only prints what it would drop.
set -euo pipefail

HOST="" PORT="5432" USER="" DAYS="30" APPLY="false"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)  HOST="$2";  shift 2;;
    --port)  PORT="$2";  shift 2;;
    --user)  USER="$2";  shift 2;;
    --days)  DAYS="$2";  shift 2;;
    --apply) APPLY="true"; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
[[ -n "$HOST" && -n "$USER" ]] || {
  echo "required: --host --user (see header for usage)" >&2; exit 2; }

now_ms="$(( $(date +%s) * 1000 ))"
cutoff_ms="$(( now_ms - DAYS * 86400 * 1000 ))"
echo "Purging retired databases older than ${DAYS} day(s) on ${HOST}:${PORT} (apply=${APPLY})"

list_dbs() {
  psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -Atqc \
    "SELECT datname FROM pg_database WHERE datname LIKE 'retired\_%';"
}

drop_db() {
  local db="$1"
  psql -h "$HOST" -p "$PORT" -U "$USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS \"${db}\";"
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
      echo "  would drop ${db} (retired $(( (now_ms - epoch) / 86400000 )) day(s) ago)"
    fi
  else
    echo "  keep ${db} (younger than ${DAYS} day(s))"
  fi
done < <(list_dbs)

echo "Done."
