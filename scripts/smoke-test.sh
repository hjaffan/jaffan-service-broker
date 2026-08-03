#!/usr/bin/env bash
#
# End-to-end smoke test for jaffan-db-broker, run against a live CF foundation where the broker is
# already registered and the plans are marketplace-visible in the current org/space.
#
# For each of the four plans it:  create-service -> create-service-key (bind) -> read credentials ->
# connect with psql/mariadb and run a trivial query -> delete-service-key (unbind) -> delete-service.
#
# Requirements on the machine running this:  cf CLI (logged in & targeted), jq, psql, mariadb (or mysql).
# The DB hosts in the returned credentials must be reachable from THIS machine (open a tunnel if the
# servers are only reachable from inside the platform).
#
# Usage:  ./scripts/smoke-test.sh
set -euo pipefail

# service::plan pairs to exercise.
PLANS=(
  "postgres:dev"
  "postgres:prod"
  "mariadb:dev"
  "mariadb:prod"
)

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
need cf; need jq; need psql
command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1 || {
  echo "missing required tool: mariadb (or mysql)" >&2; exit 1; }
MYSQL_BIN="$(command -v mariadb || command -v mysql)"

for pair in "${PLANS[@]}"; do
  service="${pair%%:*}"
  plan="${pair##*:}"
  si="smoke-${service}-${plan}"
  key="${si}-key"

  echo "=============================================================="
  echo ">> ${service} / ${plan}: create-service ${si}"
  cf create-service "${service}" "${plan}" "${si}"
  # Provision is synchronous, so the instance is ready immediately; this just confirms status.
  cf service "${si}" | grep -i status || true

  echo ">> bind (create-service-key) ${key}"
  cf create-service-key "${si}" "${key}"

  # Extract the credentials JSON. `cf service-key` prints a human header then the JSON body.
  creds_json="$(cf service-key "${si}" "${key}" | sed -n '/{/,$p')"
  host="$(echo "${creds_json}"     | jq -r '.credentials.host')"
  port="$(echo "${creds_json}"     | jq -r '.credentials.port')"
  db="$(echo "${creds_json}"       | jq -r '.credentials.database')"
  user="$(echo "${creds_json}"     | jq -r '.credentials.username')"
  password="$(echo "${creds_json}" | jq -r '.credentials.password')"
  echo "   host=${host} port=${port} db=${db} user=${user} (password hidden)"

  echo ">> connect & query as the bound user"
  if [[ "${service}" == "postgres" ]]; then
    PGPASSWORD="${password}" psql -h "${host}" -p "${port}" -U "${user}" -d "${db}" \
      -v ON_ERROR_STOP=1 -c "CREATE TABLE IF NOT EXISTS smoke (id int); INSERT INTO smoke VALUES (1); SELECT count(*) FROM smoke;"
  else
    "${MYSQL_BIN}" -h "${host}" -P "${port}" -u "${user}" "-p${password}" "${db}" \
      -e "CREATE TABLE IF NOT EXISTS smoke (id int); INSERT INTO smoke VALUES (1); SELECT count(*) FROM smoke;"
  fi

  echo ">> unbind (delete-service-key) ${key}"
  cf delete-service-key -f "${si}" "${key}"

  echo ">> delete-service ${si}"
  cf delete-service -f "${si}"
  echo "   OK: ${service}/${plan}"
done

echo "=============================================================="
echo "All four plans passed the provision -> bind -> use -> unbind -> deprovision smoke test."
