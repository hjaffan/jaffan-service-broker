# jaffan-db-broker

An [Open Service Broker API](https://github.com/openservicebrokerapi/servicebroker) (OSB v2.16)
service broker that runs as an app on Cloud Foundry and hands out **shared-instance PostgreSQL**
tenants carved out of a **BOSH-deployed postgres-ha cluster** that already exists. It creates a
database (plus roles) on the shared cluster; it never deploys infrastructure.

**Deleting a service instance never deletes data.** Deprovision *retires* the tenant database:
it is renamed to `retired_<original>_<epochMillis>` with all data intact and connections to it are
blocked. Recovering or purging a retired database is a deliberate, out-of-band operator action.

Built with **Java 21, Spring Boot 3.x, and Spring Cloud Open Service Broker 4.x**. Single Maven
module. **No JPA, no Lombok, no broker-owned database — the broker is fully stateless.** Every object
name is a deterministic function of the OSB GUIDs, so restarting the app loses nothing.

---

## Table of contents

1. [Architecture](#architecture)
2. [Catalog](#catalog)
3. [Configuration](#configuration)
4. [Build](#build)
5. [Continuous deployment (GitHub Actions)](#continuous-deployment-github-actions)
6. [Deploy to Cloud Foundry (manual)](#deploy-to-cloud-foundry-manual)
7. [Register the broker](#register-the-broker)
8. [Promote to global + enable access](#promote-to-global--enable-access)
9. [Application Security Groups (container egress)](#application-security-groups-container-egress)
10. [Verify](#verify)
11. [Smoke test](#smoke-test)
12. [Naming & statelessness](#naming--statelessness)
13. [Retirement: recover or purge](#retirement-recover-or-purge)
14. [Optional: Postgres extensions](#optional-postgres-extensions)
15. [Testing](#testing)
16. [Security notes](#security-notes)

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
                         │  multi-host JDBC, targetServerType=primary
            ┌────────────┼────────────┐
            ▼            ▼            ▼
        pg-ha node   pg-ha node   pg-ha node    ← pre-existing BOSH postgres-ha deployment
        (primary)    (standby)    (standby)
```

* **`ServiceInstanceService`** – provision a tenant database; **retire** it on deprovision; refuse
  plan changes.
* **`ServiceInstanceBindingService`** – mint / revoke per-binding credentials.
* **Catalog bean** – one service, one plan. The library serves `GET /v2/catalog` from it.
* One tiny **HikariCP pool (max 2)** pointed at the cluster's `postgres` maintenance database.
  `PG_HOST` may list every postgres-ha node; all broker connections use a multi-host JDBC URL with
  `targetServerType=primary`, so the broker follows a failover without any load balancer. The
  backend is probed with `SELECT 1` at startup; the app **fails fast** if it is unreachable.

All operations are **synchronous** — the broker never returns `202` and never implements
last-operation polling.

---

## Catalog

| Service    | Plan     | Backend                          |
|------------|----------|----------------------------------|
| `postgres` | `shared` | The BOSH postgres-ha cluster     |

The plan is `bindable: true`, `free: true`, `plan_updateable: false`. A plan change
(`PATCH /v2/service_instances`) returns **HTTP 422** — there is only one plan.

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

### postgres-ha backend

| Variable             | Required | Description                                                              |
|----------------------|----------|--------------------------------------------------------------------------|
| `PG_HOST`            | yes      | Cluster node list the **broker** connects to: `host[:port],host[:port],...`. List every postgres-ha node; connections are pinned to the primary. A single host (e.g. a VIP or BOSH DNS alias) also works. |
| `PG_PORT`            | no       | Default port for entries without an explicit one. Defaults to `5432`.    |
| `PG_ADMIN_USER`      | yes      | Admin account (must be able to CREATE DATABASE/ROLE and ALTER DATABASE). |
| `PG_ADMIN_PASSWORD`  | yes      | Admin password (never logged).                                           |
| `PG_EXTERNAL_HOST`   | no       | Endpoint list embedded in **binding credentials**, if apps must use different addresses than the broker does. Same `host[:port],...` format. |

With multiple nodes, binding credentials contain multi-host connection strings pinned to the
primary (`target_session_attrs=read-write` in `uri`, `targetServerType=primary` in `jdbcUrl`), plus
a `hosts` array and a single `host`/`port` (the first endpoint) for clients that only accept one
address.

---

## Build

Requires **JDK 21** and Maven.

```bash
mvn package
```

Produces the runnable Spring Boot jar at `target/jaffan-db-broker.jar`. Unit tests run
automatically (see [Testing](#testing)); no Docker or other external services are needed to build.

---

## Continuous deployment (GitHub Actions)

[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) builds and tests the broker on a
GitHub runner, then `cf push`es the resulting jar to your (publicly reachable) Cloud Foundry.

* **Build job** (runs on every push and PR to `main`): `mvn -B package` — compiles the broker and
  runs the unit tests. A red build blocks deployment.
* **Deploy job** (only on push to `main`): downloads the jar, installs the cf CLI v8, authenticates,
  creates the target space if needed, `cf push --no-start`, sets every configured env var, then
  `cf start`. When the `REGISTER_BROKER` variable is `true` it also registers the broker
  **globally** and runs `cf enable-service-access postgres` so the plan is marketplace-visible in
  every org (this requires the deploy account to be a CF admin).

> The classic CF Java buildpack does not compile source — that's exactly why the jar is built on the
> runner and pushed, rather than pushing `src/`.

### Configure secrets & variables

Non-sensitive values go in repository **Variables**, credentials in repository **Secrets**
(Settings → Secrets and variables → Actions). With the `gh` CLI:

```bash
# --- Variables (non-sensitive) ---
gh variable set CF_API   --body "https://api.cf.example.com"
gh variable set CF_ORG   --body "my-org"
gh variable set CF_SPACE --body "my-space"
gh variable set CF_SKIP_SSL_VALIDATION --body "false"   # "true" only for a self-signed API cert
gh variable set REGISTER_BROKER  --body "true"          # optional: auto space-scoped registration

# List every postgres-ha node (or a single VIP / BOSH DNS alias).
gh variable set PG_HOST --body "10.0.1.10:5432,10.0.1.11:5432,10.0.1.12:5432"
# gh variable set PG_PORT --body "5432"                             # optional
# gh variable set PG_EXTERNAL_HOST --body "pg.apps.internal:5432"   # optional

# --- Secrets (sensitive) ---
gh secret set CF_USERNAME     --body "ci-deployer"
gh secret set CF_PASSWORD     --body "..."
gh secret set BROKER_USER     --body "broker-admin"
gh secret set BROKER_PASSWORD --body "$(openssl rand -base64 24)"
gh secret set PG_ADMIN_USER     --body "broker_admin"
gh secret set PG_ADMIN_PASSWORD --body "..."
```

The deploy job uses a `cloud-foundry` GitHub Environment — add required reviewers there if you want a
manual approval gate before each production push. Promotion to global + `enable-service-access`
remains a one-time admin step (see [below](#promote-to-global--enable-access)).

---

## Deploy to Cloud Foundry (manual)

If you'd rather not use the pipeline, push without starting, set every variable, then start. (The
broker will fail to start until the backend is configured and reachable — that's the fail-fast
design.)

```bash
# 1. Push the app but don't start it yet.
cf push -f manifest.yml --no-start

# 2. Broker HTTP Basic credentials.
cf set-env jaffan-db-broker BROKER_USER     "broker-admin"
cf set-env jaffan-db-broker BROKER_PASSWORD "$(openssl rand -base64 24)"

# 3. postgres-ha backend (list every node; connections go to the primary).
cf set-env jaffan-db-broker PG_HOST           "10.0.1.10:5432,10.0.1.11:5432,10.0.1.12:5432"
cf set-env jaffan-db-broker PG_ADMIN_USER     "broker_admin"
cf set-env jaffan-db-broker PG_ADMIN_PASSWORD "REDACTED"
# cf set-env jaffan-db-broker PG_PORT          "5432"                    # optional
# cf set-env jaffan-db-broker PG_EXTERNAL_HOST "pg.apps.internal:5432"   # optional

# 4. Start it.
cf start jaffan-db-broker
```

Grab the app's route for the next step:

```bash
cf app jaffan-db-broker            # note the "routes:" line, e.g. jaffan-db-broker.apps.example.com
```

> The broker must be able to reach the postgres-ha nodes from its own container. Bind the egress ASG
> to the broker's space too (see [ASGs](#application-security-groups-container-egress)).

### The broker's admin account on postgres-ha

Create a dedicated role on the cluster for the broker (as a superuser, on the current primary):

```sql
CREATE ROLE broker_admin LOGIN PASSWORD '...' CREATEDB CREATEROLE;
-- lets the broker terminate tenant/binding sessions during unbind and retire
GRANT pg_signal_backend TO broker_admin;
```

No superuser needed: `CREATEDB` + `CREATEROLE` cover provision/bind/unbind, the broker grants
itself membership in each `o_x` owner role it creates (required to create and later rename the
databases they own), and `pg_signal_backend` covers connection termination. Verified against
PostgreSQL 12 and 16. Add the account to `pg_hba.conf` (or the postgres-ha release's equivalent
property) for the CF container network.

---

## Register the broker

**Space-scoped** (no CF admin required; the broker is only visible in the current space):

```bash
cf create-service-broker jaffan-db \
  "broker-admin" "<the BROKER_PASSWORD you set>" \
  https://jaffan-db-broker.apps.example.com \
  --space-scoped
```

`cf marketplace` in that space should now list `postgres`.

---

## Promote to global + enable access

Requires CF admin. Drop `--space-scoped` so the broker is registered globally, then grant access per
org.

```bash
# Register globally (same URL + credentials, no --space-scoped).
cf create-service-broker jaffan-db \
  "broker-admin" "<the BROKER_PASSWORD you set>" \
  https://jaffan-db-broker.apps.example.com

# Enable the plan for a specific org (repeat per org, or omit -o for all orgs).
cf enable-service-access postgres -p shared -o my-org
```

> If you registered space-scoped first, delete that registration
> (`cf delete-service-broker jaffan-db`) before registering globally, or use a different broker name.

---

## Application Security Groups (container egress)

App containers (and the broker itself) need egress to the postgres-ha nodes on `5432`. ASGs are
managed separately from the broker; an example lives at
[`deploy/db-egress-asg.json`](deploy/db-egress-asg.json):

```json
[
  { "protocol": "tcp", "destination": "10.0.0.0/8", "ports": "5432",
    "description": "Allow app containers to reach the postgres-ha cluster" }
]
```

> Tighten `destination` to the actual node IPs/CIDRs in your foundation before using this.

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
#   postgres   shared   Shared-instance PostgreSQL ...

cf marketplace -e postgres    # shows the shared plan
```

---

## Smoke test

[`scripts/smoke-test.sh`](scripts/smoke-test.sh) drives the full lifecycle:
`create-service → create-service-key (bind) → connect with psql using the returned creds →
delete-service-key (unbind) → delete-service` (which **retires** the database).

```bash
# Requires: cf (logged in & targeted), jq, psql, and network reach to the postgres-ha nodes.
./scripts/smoke-test.sh
```

Each run leaves one `retired_*` database on the cluster (that's the point — nothing is dropped);
clean them out with [`scripts/purge-retired.sh`](scripts/purge-retired.sh) when needed.

### Verify tenants directly (SQL)

Connect to the cluster as the admin account and confirm the tenants the broker created:

```sql
-- live tenant databases
SELECT datname FROM pg_database WHERE datname LIKE 'si\_%';
-- retired tenant databases
SELECT datname FROM pg_database WHERE datname LIKE 'retired\_%';
-- instance owner roles (o_) and binding login roles (b_)
SELECT rolname FROM pg_roles WHERE rolname LIKE 'o\_%' OR rolname LIKE 'b\_%';
```

---

## Naming & statelessness

Every SQL object name is derived purely from the OSB GUIDs, which is exactly what lets the broker keep
no state:

| Object                         | Name                                      |
|--------------------------------|-------------------------------------------|
| Instance database              | `si_<instance_guid>` (hyphens → `_`)      |
| Owner role (NOLOGIN)           | `o_<instance_guid>`                       |
| Binding role                   | `b_<binding_guid>`                        |
| Retired database               | `retired_<original>_<epochMillis>`        |

Passwords are 32-char alphanumerics from `SecureRandom`. All identifiers pass through a single
validation/quoting utility ([`Identifiers`](src/main/java/com/jaffan/broker/naming/Identifiers.java))
that rejects anything outside `[a-z0-9_]` and enforces the 63-char Postgres limit. SQL is never built
by naive concatenation of unvalidated input.

**Idempotency & status codes** (all derived from the backing cluster, nothing stored):

| Case                                              | Result   |
|---------------------------------------------------|----------|
| Provision, same GUID already exists               | `200`    |
| Provision, same GUID previously retired           | `201` (a fresh, empty database; the retired copy is untouched) |
| Deprovision unknown / already-retired instance    | `410`    |
| Bind an already-existing binding                  | `409`\*  |
| Unbind unknown binding                            | `410`    |
| Plan change (`PATCH`)                             | `422`    |

\* A binding password cannot be reproduced statelessly, so a repeat bind of an existing binding is a
`409` rather than fabricating credentials that wouldn't match the live role.

---

## Retirement: recover or purge

`cf delete-service` **never drops anything**. The broker:

1. terminates every connection to the tenant database;
2. renames it: `ALTER DATABASE si_x RENAME TO retired_si_x_<epochMillis>`;
3. freezes it: `ALTER DATABASE retired_si_x_<epochMillis> WITH ALLOW_CONNECTIONS false`.

The owner role `o_x` is **kept** — it still owns the retired database. Binding roles are already
gone (CF unbinds before deleting an instance).

### Recover a retired database (operator, as admin)

```sql
-- make it reachable again
ALTER DATABASE "retired_si_<guid>_<epoch>" WITH ALLOW_CONNECTIONS true;
-- optionally give it back its original name (e.g. before re-registering it with CF)
ALTER DATABASE "retired_si_<guid>_<epoch>" RENAME TO "si_<guid>";
```

### Purge retired databases (operator, deliberate)

The broker will never do this for you. When you genuinely want the space back, use the bundled admin
script, which drops retired databases older than N days (dry-run by default, add `--apply` to
actually drop):

```bash
# Dry run:
PGPASSWORD=... ./scripts/purge-retired.sh --host 10.0.1.10 --port 5432 --user broker_admin --days 30

# Actually drop:
PGPASSWORD=... ./scripts/purge-retired.sh --host 10.0.1.10 --port 5432 --user broker_admin --days 30 --apply
```

An owner role `o_<guid>` whose retired database has been purged (and that owns nothing else) can then
be dropped with `DROP ROLE IF EXISTS "o_<guid>";`.

---

## Optional: Postgres extensions

On provision you may request a **whitelisted** set of Postgres extensions
(`pgcrypto`, `uuid-ossp`, `pg_trgm`). Anything outside the whitelist is rejected.

```bash
cf create-service postgres shared my-pg -c '{"extensions": ["pgcrypto", "uuid-ossp"]}'
```

Extensions are applied inside the tenant database at provision time.

---

## Testing

```bash
mvn package     # unit tests (naming/sanitization, host-list parsing, routing, catalog)
```

* **Unit tests** — identifier validation/sanitization & length limits, password generation,
  `PG_HOST` list parsing, plan→backend routing, and the fixed catalog shape/IDs. These run during
  `mvn package` and need nothing but a JDK.
* **End-to-end validation** happens against the real platform: after deploying and registering the
  broker, run [`scripts/smoke-test.sh`](scripts/smoke-test.sh), which drives
  **provision → bind → use (psql) → unbind → retire** through `cf` against the live postgres-ha
  cluster (see [Smoke test](#smoke-test)).

---

## Security notes

* Passwords and credential JSON are **never** logged at any level. The structured logger has no code
  path that accepts a secret.
* Logging is **single-line key=value to stdout**: `op`, instance/binding GUID, `plan`, `backend`,
  `outcome`, `duration_ms`. No secret ever reaches `cf logs`.
* The startup banner logs one **masked** line for the backend — hosts and admin user only, never the
  password.
* HTTP Basic on all `/v2/**` endpoints; `/actuator/health` open for the CF health check; sessions
  stateless; only the health actuator endpoint is exposed.
