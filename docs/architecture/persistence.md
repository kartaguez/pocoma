# PostgreSQL Persistence & SQL Baselines

Pocoma enforces physical data separation between the write store (`pocoma_write`) and the read store (`pocoma_read`), hosted on a local `postgres:17.6-alpine` instance with no host port published.

## Strict WRITE / READ Database Separation

| Aspect | WRITE Store | READ Store |
| :--- | :--- | :--- |
| PostgreSQL database | `pocoma_write` | `pocoma_read` |
| Main schema | `v3_write` | `v3_read` (+ `public.read_maintenance_state` table) |
| Writing runtimes | `http-write`, `command-worker` | `results-worker`, `business-read-worker` |
| Read-only runtimes | `results-worker`, `business-read-worker` (via `write-source-read` grants) | `http-read` |
| Canonical SQL locations | `db/write/migration` | `db/read/migration` and `db/read/availability/migration` |

Least-privilege grants and local schema setup are applied by [`scripts/operations/setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) using dedicated SQL scripts under `scripts/v3/`:

- `configure-l3-identity-logging.sql`
- `bootstrap-read-maintenance.sql`
- `grant-l7-http-read.sql`
- `grant-l7-write-source-read.sql`
- `bootstrap-l2-routing.sql`

## Active SQL Baselines (V1)

Each store has a **single active baseline** `V1__initial_schema.sql`:

- **WRITE**: [`app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql`](../../app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql)
  - `37` tables, `221` columns, `271` constraints, `116` indexes, `8` sequences, `38` functions, `62` triggers.
- **READ**: [`app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql)
  - `14` tables, `74` columns, `67` constraints, `22` indexes, `5` functions, `16` triggers.

Current READ initialization applies V2 (balance aggregates), V3 (PotVersion Radix-16) and V4 (shared PotBalance storage) after the baseline. It then applies [READ V5](../../app/infra-postgres-read/src/main/resources/db/read/availability/migration/V5__projection_availability.sql) from the separate availability location. The earlier CommandResult pilot migration is retained in the isolated `db/read/pilot/migration` location for historical qualification.

The availability migration requires an empty READ authority. See [READ reconstruction and availability](../operations/recovery.md#read-reconstruction-and-availability) for first activation on a populated database.

## Consolidated Baselines & Isolated Historical Migrations

The distribution starts from consolidated V1 baselines. Earlier migration histories are isolated outside the production path:

- **19 archived V3 migrations** (`11` WRITE files and `8` READ files): kept for audit and traceability in [`docs/archive/v3-sql-migrations/`](../archive/v3-sql-migrations/MANIFEST.json) with their `MANIFEST.json`. See [Archive Index](../archive/README.md).
- **45 historical V2 migrations isolated in test resources** (never packaged in production application JARs):
  - `app/infra-postgres-write/src/test/resources/db/migration/`: `31` V2 SQL files;
  - `app/infra-postgres-read/src/test/resources/db/read-store/migration/`: `14` V2 SQL files (alongside `app/infra-postgres-read/src/test/resources/db/primary-test-migration/V1__initialize_primary_test.sql` for tests).

These archives are **never** needed to install or start a local Pocoma instance.

## Bootstrap Integrity Check & Incompatible Instance Refusal

During database bootstrap by `./pocoma-local`, a fingerprint is recorded in the control table:

```sql
public.pocoma_local_bootstrap(fingerprint text PRIMARY KEY)
```

On every subsequent startup, the orchestrator compares the existing database state against the current repository inputs:

| Refusal Code | Detected Condition | Expected Action |
| :--- | :--- | :--- |
| `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` | Legacy Flyway history predating the consolidated V1 baseline | Do not migrate manually; use `reset` or `destroy`, or pick a new `--instance` |
| `INCOMPATIBLE-EXISTING-SCHEMA` | Existing schema missing the expected marker or structurally divergent | Do not force in place; reset via `reset` or isolate in another instance |
| `DB-INPUT-DRIFT` | Current SQL/config fingerprint differs from `pocoma_local_bootstrap` | Explicitly reset (`reset --confirm RESET:<instance>`) if the change is intended |

## Coordination & Routing Tables

In addition to business tables in `v3_write` and `v3_read`, runtimes and the orchestrator rely on technical tables for lease and worker tracking:

- `pocoma_v3_lease_local`: current ownership and expiration of L2 partition leases (`lease-duration-ms=30000`, renewed via `renewBatch`).
- `pocoma_v3_worker_state`: observed worker state and lifecycle metadata used during startup and recovery checks ([Crash Recovery & Lifecycle](../operations/recovery.md)).
