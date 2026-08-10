#!/usr/bin/env bash
#
# End-to-end smoke test for jaffan-db-broker, run against a live CF foundation where the broker is
# already registered and the postgres/shared plan is marketplace-visible in the current org/space.
#
# It drives:  create-service -> create-service-key (bind) -> read credentials -> connect with psql
# and run a trivial query -> delete-service-key (unbind) -> delete-service.
#
# NOTE: delete-service RETIRES the tenant database (renames it to retired_<db>_<epoch> and blocks
# connections); nothing is dropped. Re-running this smoke test therefore leaves one retired_*
# database per run on the cluster — clean them out with scripts/purge-retired.sh when needed.
#
# Requirements on the machine running this:  cf CLI (logged in & targeted), jq, psql.
# The DB hosts in the returned credentials must be reachable from THIS machine (open a tunnel if the
# cluster is only reachable from inside the platform).
#
# Usage:  ./scripts/smoke-test.sh
set -euo pipefail

SERVICE="postgres"
PLAN="shared"
SI="smoke-${SERVICE}-${PLAN}"
KEY="${SI}-key"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
need cf; need jq; need psql

echo "=============================================================="
echo ">> ${SERVICE} / ${PLAN}: create-service ${SI}"
cf create-service "${SERVICE}" "${PLAN}" "${SI}"
# Provision is synchronous, so the instance is ready immediately; this just confirms status.
cf service "${SI}" | grep -i status || true

echo ">> bind (create-service-key) ${KEY}"
cf create-service-key "${SI}" "${KEY}"

# Extract the credentials JSON. `cf service-key` prints a human header then the JSON body.
creds_json="$(cf service-key "${SI}" "${KEY}" | sed -n '/{/,$p')"
uri="$(echo "${creds_json}"  | jq -r '.credentials.uri')"
db="$(echo "${creds_json}"   | jq -r '.credentials.database')"
user="$(echo "${creds_json}" | jq -r '.credentials.username')"
echo "   db=${db} user=${user} (connecting via the multi-host uri, pinned to the primary)"

echo ">> connect & query as the bound user"
# Use the uri credential: with several cluster nodes, host/port alone may point at a standby;
# the uri carries every node plus target_session_attrs=read-write, so libpq finds the primary.
psql "${uri}" -v ON_ERROR_STOP=1 \
  -c "CREATE TABLE IF NOT EXISTS smoke (id int); INSERT INTO smoke VALUES (1); SELECT count(*) FROM smoke;"

echo ">> unbind (delete-service-key) ${KEY}"
cf delete-service-key -f "${SI}" "${KEY}"

echo ">> delete-service ${SI} (retires the database; nothing is dropped)"
cf delete-service -f "${SI}"

echo "=============================================================="
echo "Smoke test passed: provision -> bind -> use -> unbind -> retire."
