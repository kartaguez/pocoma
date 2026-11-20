# Pocoma V3 — L7-C2-B2.2 Gate Report

- **Date**: 2026-11-20
- **Branch**: `feat/v3-l7`
- **Start HEAD**: `88e59b08d296244ad9990e9e2cf8d34d88b71d71`
- **Verdict**: `L7_C2_B2_2_QUALIFIED`

## Verification Scope (`Reactor_Verification_Policy.md` §30)

- **Expected production impact**:
  - `infra-postgres-read` (atomic promotion of `V4__pot_balance_shared_storage.sql`, rewrite of `JdbcPotBalanceStore` on V4 content-addressed storage, deletion of legacy `PotBalanceAggregateJson`)
  - `infra-postgres-core` (`BootstrapGuard.READ_UPDATES` + `read_pot_balance_content`, `read_pot_balance_aggregate_content`)
  - `compose-polling-nonburnable-materialize-postgres` (`ReadinessOracle.checkRelationalIntegrity` checking `content_hash` FK consistency on balance and aggregate tables)
- **Observed production impact**: Exact match with expected production impact. Zero changes to `ReadPot`, `supra-web-read`, `PotVersion` Radix-16 (`radix/*`, `JdbcPotVersionStore`, `MaterializePotVersion`, `V3__pot_version_radix16.sql`), `infra-postgres-write`, `domain-pot`, `domain-identity`.
- **Declared slices**: Primary `PROJECTION` (`runtime-worker-materialize -am`), Secondary `WEB` (`runtime-web-write,runtime-web-read -am`), Global architecture gate `architecture-tests -am`.
- **Verification commands executed**:
  1. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-read,compose-polling-nonburnable-materialize-postgres -am clean test` — **BUILD SUCCESS** (22 modules, 0 failures, 0 errors, 0 skipped; 47.2 s)
  2. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize,runtime-web-write,runtime-web-read -am test` — **BUILD SUCCESS** (39 modules, 0 failures, 0 errors, 0 skipped; 27:57 min)
  3. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl architecture-tests -am test` — **BUILD SUCCESS** (43 modules, 0 failures, 0 errors, 0 skipped; 01:20 h)
- **Unexpected slice crossings**: `NONE`
- **Global architecture gate**: `RUN` (`architecture-tests` 52/52 PASS, required by addition of `V4__pot_balance_shared_storage.sql` to `src/main/resources/db/read/migration/`)
- **Full reactor**: `NOT REQUIRED` as standalone step (all 43 modules were transitively built and verified via `architecture-tests -am test`; dedicated B2.4 certification remains deferred to B2.4)
- **Database verification**: `CURRENT` (`V1 -> V2 -> V3 -> V4` on PostgreSQL 17.11 Testcontainers)

## Test Results Summary

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `infra-postgres-core` | 3 | 12 | 0 | 0 | 0 |
| `infra-postgres-read` | 12 | 39 | 0 | 0 | 0 |
| `compose-polling-nonburnable-materialize-postgres` | 1 | 13 | 0 | 0 | 0 |
| `runtime-web-read` | 6 | 42 | 0 | 0 | 0 |
| `runtime-worker-materialize` | 15 | 115 | 0 | 0 | 0 |
| `runtime-monolith` | 11 | 40 | 0 | 0 | 0 |
| `architecture-tests` | 10 | 52 | 0 | 0 | 0 |

## Failures Encountered & Fixed During Qualification

1. `PotBalanceReadStorePostgresTest.independentDeduplicationSituationsACDAndHashCollisionRollback`: `ReadStoreFixture.write(...)` wraps inner `IllegalStateException` in `AttemptTransactions.Failure`; updated assertion to verify causal `IllegalStateException` with `"Divergent read_pot_balance[_aggregate]_content"` and confirmed full transaction rollback (`0` pointer rows).
2. `SubobjectPostgresFixture.java:251`: hardcoded `assertEquals(3, migrateCurrentBaseline())` updated to `4` for `V1..V4`.

## Demonstrated Invariants

- Exact financial value equivalence pre/post V4 across all `PROJECTION`, `WEB`, and `runtime-monolith` suites.
- Independent content deduplication for Situations A, C, and D (`read_pot_balance_content` vs `read_pot_balance_aggregate_content`).
- Byte-for-byte exact equality check on `ON CONFLICT (content_hash) DO NOTHING`; simulated SHA-256 collision / divergent payload raises `IllegalStateException` and rolls back the entire transaction atomically.
- Direct historical reads via single PK join (`0` version chains, `0` secondary indexes).
- Concurrency, out-of-order `N-m`, replay idempotence, transactional publication with fencing (`requireCompletionReady`), and immutability preserved.
- `BootstrapGuard` refuses excess privileges on `read_pot_balance_content` and `read_pot_balance_aggregate_content`; `ReadinessOracle` validates `content_hash` FK integrity.
- Legacy `PotBalanceAggregateJson` removed with zero remaining references.
