# jaffan-db-broker

An [Open Service Broker API](https://github.com/openservicebrokerapi/servicebroker) (OSB v2.16)
service broker that runs as an app on Cloud Foundry and hands out **shared-instance PostgreSQL and
MariaDB** tenants carved out of database servers that already exist. It provisions a database (plus
roles/users) on a shared server; it never deploys infrastructure.

Built with **Java 21, Spring Boot 3.x, and Spring Cloud Open Service Broker 4.x**. Single Maven
module. **No JPA, no Lombok, no broker-owned database — the broker is fully stateless.** Every object
name is a deterministic function of the OSB GUIDs, so restarting the app loses nothing.

---

## Table of contents

1. [Architecture](#architecture)
2. [Catalog](#catalog)
3. [Configuration](#configuration)
4. [Build](#build)
5. [Deploy to Cloud Foundry](#deploy-to-cloud-foundry)
6. [Register the broker](#register-the-broker)
7. [Promote to global + enable access](#promote-to-global--enable-access)
8. [Application Security Groups (container egress)](#application-security-groups-container-egress)
9. [Verify](#verify)
10. [Smoke test](#smoke-test)
11. [Naming & statelessness](#naming--statelessness)
12. [Deprovision modes & purging soft-deleted tenants](#deprovision-modes--purging-soft-deleted-tenants)
13. [Optional: Postgres extensions](#optional-postgres-extensions)
14. [Testing](#testing)
15. [Security notes](#security-notes)

---

## Architecture

```
        cf create-service / bind / unbind / delete
                         │  (OSB v2.16, HTTP Basic)
                         ▼
             ┌───────────────────────┐
             │   jaffan-db-broker    │   1 stateless app instance on CF
             │  (Spring Boot 3, J21) │
             └───────────┬───────────┘
        plan_id ─────────┤ routes to exactly one backend
        ┌────────────┬───┴────────┬─────────────┐
        ▼            ▼            ▼             ▼
   PG dev       PG prod      MariaDB dev   MariaDB prod   ← pre-existing shared servers
```

* **`ServiceInstanceService`** – provision / deprovision a tenant database, and refuse plan changes.
* **`ServiceInstanceBindingService`** – mint / revoke per-binding credentials.
* **Catalog bean** – two services, two plans each. The library serves `GET /v2/catalog` from it.
* One tiny **HikariCP pool (max 2)** per backend, pointed at that engine's maintenance database
  (`postgres` / `mysql`). Every backend is probed with `SELECT 1` at startup; the app **fails fast**
  if any is unreachable.

All operations are **synchronous** — the broker never returns `202` and never implements
last-operation polling.

---

## Catalog

| Service    | Plan   | Backend server        |
|------------|--------|-----------------------|
| `postgres` | `dev`  | PostgreSQL dev server |
| `postgres` | `prod` | PostgreSQL prod server|
| `mariadb`  | `dev`  | MariaDB dev server    |
| `mariadb`  | `prod` | MariaDB prod server   |

All plans: `bindable: true`, `free: true`, `plan_updateable: false`. A plan change
(`PATCH /v2/service_instances`) returns **HTTP 422** — the dev and prod plans live on physically
separate servers, so switching plans would require a cross-cluster data migration, which this broker
does not do.

**Service/plan UUIDs are fixed constants** in
[`CatalogIds.java`](src/main/java/com/jaffan/broker/catalog/CatalogIds.java) and **must never change
once the broker is registered** — Cloud Controller keys every existing instance and binding to them.

---

## Configuration

All configuration is via **environment variables**. No secret is ever written to a config file or the
logs.

### Broker auth

| Variable          | Required | Description                                                     |
|-------------------|----------|-----------------------------------------------------------------|
| `BROKER_USER`     | yes      | HTTP Basic username Cloud Controller uses to call `/v2/**`.     |
| `BROKER_PASSWORD` | yes      | HTTP Basic password.                                            |

`/actuator/health` is **unauthenticated** (CF health check); everything under `/v2/**` requires Basic.

### Per backend

Replace `<PREFIX>` with each of `PG_DEV`, `PG_PROD`, `MARIA_DEV`, `MARIA_PROD`:

| Variable                   | Required | Description                                                              |
|----------------------------|----------|--------------------------------------------------------------------------|
| `<PREFIX>_HOST`            | yes      | Host the **broker** connects to.                                         |
| `<PREFIX>_PORT`            | no       | Defaults to `5432` (Postgres) / `3306` (MariaDB).                        |
| `<PREFIX>_ADMIN_USER`      | yes      | Admin/root account (must be able to CREATE DATABASE/ROLE/USER).          |
| `<PREFIX>_ADMIN_PASSWORD`  | yes      | Admin password (never logged).                                          |
| `<PREFIX>_EXTERNAL_HOST`   | no       | Host embedded in **binding credentials**, if apps must use a different address than the broker does. |

### Behaviour

| Variable           | Required | Description                                                       |
|--------------------|----------|-------------------------------------------------------------------|
| `DEPROVISION_MODE` | no       | `soft` (default) or `drop`. See [Deprovision modes](#deprovision-modes--purging-soft-deleted-tenants). |

---

## Build

Requires **JDK 21** and Maven.

```bash
mvn package
```

Produces the runnable Spring Boot jar at `target/jaffan-db-broker.jar`. Unit tests run automatically;
the Testcontainers integration tests are `*IT` classes bound to `mvn verify` (see
[Testing](#testing)).

---

## Deploy to Cloud Foundry

Push without starting, set every variable, then start. (The broker will fail to start until the
backends are configured and reachable — that's the fail-fast design.)

```bash
# 1. Push the app but don't start it yet.
cf push -f manifest.yml --no-start

# 2. Broker HTTP Basic credentials.
cf set-env jaffan-db-broker BROKER_USER     "broker-admin"
cf set-env jaffan-db-broker BROKER_PASSWORD "$(openssl rand -base64 24)"

# 3. PostgreSQL dev backend.
cf set-env jaffan-db-broker PG_DEV_HOST           "pg-dev.db.internal"
cf set-env jaffan-db-broker PG_DEV_PORT           "5432"
cf set-env jaffan-db-broker PG_DEV_ADMIN_USER     "broker_admin"
cf set-env jaffan-db-broker PG_DEV_ADMIN_PASSWORD "REDACTED"
# cf set-env jaffan-db-broker PG_DEV_EXTERNAL_HOST "pg-dev.apps.internal"   # optional

# 4. PostgreSQL prod backend.
cf set-env jaffan-db-broker PG_PROD_HOST           "pg-prod.db.internal"
cf set-env jaffan-db-broker PG_PROD_PORT           "5432"
cf set-env jaffan-db-broker PG_PROD_ADMIN_USER     "broker_admin"
cf set-env jaffan-db-broker PG_PROD_ADMIN_PASSWORD "REDACTED"
# cf set-env jaffan-db-broker PG_PROD_EXTERNAL_HOST "pg-prod.apps.internal" # optional

# 5. MariaDB dev backend.
cf set-env jaffan-db-broker MARIA_DEV_HOST           "maria-dev.db.internal"
cf set-env jaffan-db-broker MARIA_DEV_PORT           "3306"
cf set-env jaffan-db-broker MARIA_DEV_ADMIN_USER     "root"
cf set-env jaffan-db-broker MARIA_DEV_ADMIN_PASSWORD "REDACTED"
# cf set-env jaffan-db-broker MARIA_DEV_EXTERNAL_HOST "maria-dev.apps.internal"  # optional

# 6. MariaDB prod backend.
cf set-env jaffan-db-broker MARIA_PROD_HOST           "maria-prod.db.internal"
cf set-env jaffan-db-broker MARIA_PROD_PORT           "3306"
cf set-env jaffan-db-broker MARIA_PROD_ADMIN_USER     "root"
cf set-env jaffan-db-broker MARIA_PROD_ADMIN_PASSWORD "REDACTED"
# cf set-env jaffan-db-broker MARIA_PROD_EXTERNAL_HOST "maria-prod.apps.internal" # optional

# 7. Deprovision behaviour (optional; defaults to soft).
cf set-env jaffan-db-broker DEPROVISION_MODE "soft"

# 8. Start it.
cf start jaffan-db-broker
```

Grab the app's route for the next step:

```bash
cf app jaffan-db-broker            # note the "routes:" line, e.g. jaffan-db-broker.apps.example.com
```

> The broker must be able to reach the four DB hosts from its own container. Bind the egress ASG to
> the broker's space too (see [ASGs](#application-security-groups-container-egress)).

---

## Register the broker

**Space-scoped** (no CF admin required; the broker is only visible in the current space):

```bash
cf create-service-broker jaffan-db \
  "broker-admin" "<the BROKER_PASSWORD you set>" \
  https://jaffan-db-broker.apps.example.com \
  --space-scoped
```

`cf marketplace` in that space should now list `postgres` and `mariadb`.

---

## Promote to global + enable access

Requires CF admin. Drop `--space-scoped` so the broker is registered globally, then grant access per
plan and org.

```bash
# Register globally (same URL + credentials, no --space-scoped).
cf create-service-broker jaffan-db \
  "broker-admin" "<the BROKER_PASSWORD you set>" \
  https://jaffan-db-broker.apps.example.com

# Enable each plan for a specific org (repeat per org, or omit -o for all orgs).
cf enable-service-access postgres -p dev  -o my-org
cf enable-service-access postgres -p prod -o my-org
cf enable-service-access mariadb  -p dev  -o my-org
cf enable-service-access mariadb  -p prod -o my-org
```

> If you registered space-scoped first, delete that registration
> (`cf delete-service-broker jaffan-db`) before registering globally, or use a different broker name.

---

## Application Security Groups (container egress)

App containers (and the broker itself) need egress to the DB hosts on `5432`/`3306`. ASGs are managed
separately from the broker; an example lives at [`deploy/db-egress-asg.json`](deploy/db-egress-asg.json):

```json
[
  { "protocol": "tcp", "destination": "10.0.0.0/8", "ports": "5432",
    "description": "Allow app containers to reach the shared PostgreSQL servers (dev + prod)" },
  { "protocol": "tcp", "destination": "10.0.0.0/8", "ports": "3306",
    "description": "Allow app containers to reach the shared MariaDB servers (dev + prod)" }
]
```

> Tighten `destination` to the actual DB host IPs/CIDRs in your foundation before using this.

Create and bind it:

```bash
cf create-security-group db-egress deploy/db-egress-asg.json

# Bind to a specific org/space for running apps:
cf bind-security-group db-egress my-org my-space --lifecycle running

# ...or globally for all running apps:
cf bind-running-security-group db-egress
```

---

## Verify

```bash
cf marketplace
# expect:
#   postgres   dev, prod   Shared-instance PostgreSQL ...
#   mariadb    dev, prod   Shared-instance MariaDB ...

cf marketplace -e postgres    # shows the dev/prod plans
cf marketplace -e mariadb
```

---

## Smoke test

[`scripts/smoke-test.sh`](scripts/smoke-test.sh) drives the full lifecycle for **all four plans**:
`create-service → create-service-key (bind) → connect with psql/mariadb using the returned creds →
delete-service-key (unbind) → delete-service`.

```bash
# Requires: cf (logged in & targeted), jq, psql, mariadb (or mysql), and network reach to the DB hosts.
./scripts/smoke-test.sh
```

### Verify tenants directly (SQL)

Connect to a backend as its admin account and confirm the tenants the broker created:

**PostgreSQL**
```sql
-- tenant databases
SELECT datname FROM pg_database WHERE datname LIKE 'si\_%';
-- instance owner roles (o_) and binding login roles (b_)
SELECT rolname FROM pg_roles WHERE rolname LIKE 'o\_%' OR rolname LIKE 'b\_%';
```

**MariaDB**
```sql
-- tenant schemas
SELECT schema_name FROM information_schema.SCHEMATA WHERE schema_name LIKE 'si\_%';
-- binding users
SELECT User, Host FROM mysql.user WHERE User LIKE 'b\_%';
```

---

## Naming & statelessness

Every SQL object name is derived purely from the OSB GUIDs, which is exactly what lets the broker keep
no state:

| Object                         | Name                                      |
|--------------------------------|-------------------------------------------|
| Instance database              | `si_<instance_guid>` (hyphens → `_`)      |
| Postgres owner role (NOLOGIN)  | `o_<instance_guid>`                       |
| Binding role / user            | `b_<binding_guid>`                        |
| Soft-deleted database          | `deleted_<original>_<epochMillis>`        |

Passwords are 32-char alphanumerics from `SecureRandom`. All identifiers pass through a single
validation/quoting utility ([`Identifiers`](src/main/java/com/jaffan/broker/naming/Identifiers.java))
that rejects anything outside `[a-z0-9_]` and enforces the length limits (≤63 Postgres, ≤64 MariaDB).
SQL is never built by naive concatenation of unvalidated input.

**Idempotency & status codes** (all derived from the backing servers, nothing stored):

| Case                                              | Result   |
|---------------------------------------------------|----------|
| Provision, same GUID + same plan already exists   | `200`    |
| Provision, same GUID + different plan             | `409`    |
| Deprovision unknown / already-deleted instance    | `410`    |
| Bind an already-existing binding                  | `409`\*  |
| Unbind unknown binding                            | `410`    |
| Plan change (`PATCH`)                             | `422`    |

\* A binding password cannot be reproduced statelessly, so a repeat bind of an existing binding is a
`409` rather than fabricating credentials that wouldn't match the live role.

---

## Deprovision modes & purging soft-deleted tenants

Set by `DEPROVISION_MODE`.

### `soft` (default) — recoverable

* **PostgreSQL** — terminate all connections to `si_x`, then
  `ALTER DATABASE si_x RENAME TO deleted_si_x_<epoch>`. The owner role `o_x` is **kept** because it
  still owns the renamed database.
* **MariaDB** — create `deleted_si_x_<epoch>`, `RENAME TABLE` every **base table** across, then drop
  the now-empty `si_x`. **Views and routines are NOT moved** — they are dropped with the original
  schema. If you need them, move them manually before deprovisioning.

**Manual purge of soft-deleted data:**

PostgreSQL (as admin, connected to `postgres`):
```sql
-- list parked databases
SELECT datname FROM pg_database WHERE datname LIKE 'deleted\_%';
-- purge one (drop the database first, then its retained owner role if no longer needed)
DROP DATABASE "deleted_si_<guid>_<epoch>";
DROP ROLE IF EXISTS "o_<guid>";
```

MariaDB (as admin):
```sql
-- list parked schemas
SELECT schema_name FROM information_schema.SCHEMATA WHERE schema_name LIKE 'deleted\_%';
-- purge one
DROP DATABASE `deleted_si_<guid>_<epoch>`;
```

Or use the bundled admin script, which drops parked databases older than N days (dry-run by default,
add `--apply` to actually drop):

```bash
# Dry run:
PGPASSWORD=... ./scripts/purge-soft-deleted.sh --engine postgres --host pg-prod.db.internal --port 5432 --user broker_admin --days 7
MYSQL_PWD=...  ./scripts/purge-soft-deleted.sh --engine mariadb  --host maria-prod.db.internal --port 3306 --user root --days 7

# Actually drop:
PGPASSWORD=... ./scripts/purge-soft-deleted.sh --engine postgres --host pg-prod.db.internal --port 5432 --user broker_admin --days 7 --apply
```

### `drop` — irreversible

* **PostgreSQL** — terminate connections, `DROP DATABASE si_x`, `DROP ROLE o_x`.
* **MariaDB** — `DROP DATABASE si_x`.

Repeated deletes are idempotent: the second `cf delete-service` returns `410` to Cloud Controller
without error.

---

## Optional: Postgres extensions

On provision you may request a **whitelisted** set of Postgres extensions
(`pgcrypto`, `uuid-ossp`, `pg_trgm`). Anything outside the whitelist is rejected.

```bash
cf create-service postgres dev my-pg -c '{"extensions": ["pgcrypto", "uuid-ossp"]}'
```

Extensions are applied inside the tenant database at provision time. MariaDB has no equivalent and
ignores the parameter.

---

## Testing

```bash
mvn package     # unit tests (naming/sanitization, plan→backend routing, catalog)
mvn verify      # additionally runs the Testcontainers integration tests
```

* **Unit tests** — identifier validation/sanitization & length limits, password generation,
  plan→backend routing, and the fixed catalog shape/IDs. These run during `mvn package`.
* **Integration tests** (`*IT`, bound to `mvn verify`) — spin up `postgres:16` and `mariadb:11` with
  [Testcontainers](https://testcontainers.com/) and drive the full
  **provision → bind → use → unbind → deprovision** lifecycle for both engines, including:
  * two bindings to one instance yield **distinct** credentials, both connect, dropping one leaves the
    other and all data intact;
  * the **"app creates a table, unbind still succeeds, and a second binding still sees the table"**
    case (the reason Postgres bindings default to `SET ROLE o_x`);
  * `soft` deprovision parks the database, `drop` deprovision removes it (and the Postgres owner role).

  They require a running Docker daemon and **self-skip** (`@Testcontainers(disabledWithoutDocker = true)`)
  when Docker is absent, so `mvn verify` still passes on machines without it.

---

## Security notes

* Passwords and credential JSON are **never** logged at any level. The structured logger has no code
  path that accepts a secret.
* Logging is **single-line key=value to stdout**: `op`, instance/binding GUID, `plan`, `backend`,
  `outcome`, `duration_ms`. No secret ever reaches `cf logs`.
* The startup banner logs one **masked** line per backend — host and admin user only, never the
  password.
* HTTP Basic on all `/v2/**` endpoints; `/actuator/health` open for the CF health check; sessions
  stateless; only the health actuator endpoint is exposed.
