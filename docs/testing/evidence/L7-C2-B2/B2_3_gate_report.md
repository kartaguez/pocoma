# Pocoma V3 — L7-C2-B2.3 Gate Report

- **Date**: 2026-11-20
- **Branch**: `feat/v3-l7`
- **Start HEAD**: `db4c3d0882cef0b40eef4e28d858803387b85b37`
- **Verdict**: `L7_C2_B2_3_QUALIFIED`

## Verification Scope (`Reactor_Verification_Policy.md` §30)

- **Expected production impact**:
  - `port-projection` (`PotBalanceStore`: additive `AggregateRef`, `ContentWriteOutcome`, `WriteResult`, `readLatestAggregateRefBefore`, `readAggregateByHash`, `linkExistingVersion`, `requireVersionHashesStored`, `insertOrVerifyWithResult`, `insertOrVerifyAggregateWithResult`; all 8 existing methods preserved)
  - `infra-postgres-read` (`JdbcPotBalanceStore`: lightweight ref lookup with 0 TOAST reads, content-hash aggregate lookup, atomic pointer linking, fencing hash verification, encode duration + inserted/reused outcome reporting)
  - `engine-materialize-pot` (`MaterializePotBalance`, `PotBalanceObservation`: formally proven ARB-3 `EMPTY_DELTA_REUSE` fast path with toggleable `emptyDeltaReuseEnabled` in `Settings`, `Path.REUSE`, `Reason.EMPTY_DELTA_REUSE`, `ContentOutcome`, `contentWritten`, `contentEncoded`)
  - `compose-polling-nonburnable-materialize-postgres` (`MicrometerPotBalanceObservation`: bounded low-cardinality counters `pocoma.v3.potbalance.content.write{target,outcome}`, `pocoma.v3.potbalance.materialize.path{path=reuse,reason=empty_delta_reuse}`, and timer `pocoma.v3.potbalance.encode.duration{target}`)
- **Observed production impact**: Exact match with expected production impact. Zero changes to `ReadPot`, `PotVersion` Radix-16, `infra-postgres-write`, `domain-pot`, `domain-identity`, or `V1..V4` migrations.
- **Declared slices**: Primary `PROJECTION` (`runtime-worker-materialize -am`), Secondary `WEB` (`runtime-web-read -am`).
- **Verification commands executed**:
  1. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl engine-materialize-pot,infra-postgres-read,compose-polling-nonburnable-materialize-postgres -am test` — **BUILD SUCCESS** (22 modules, 0 failures, 0 errors, 0 skipped; 41.7 s)
  2. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize,runtime-web-read -am test` — **BUILD SUCCESS** (38 modules, 0 failures, 0 errors, 0 skipped; 27:14 min)
- **Unexpected slice crossings**: `NONE`
- **Global architecture gate**: `NOT REQUIRED` (no module dependencies or migrations modified)
- **Full reactor**: `NOT REQUIRED` (deferred to B2.4)
- **Database verification**: `CURRENT` (`V1..V4` on PostgreSQL 17.11 Testcontainers)

## Test Results Summary

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `engine-materialize-pot` | 3 | 8 | 0 | 0 | 0 |
| `infra-postgres-read` | 12 | 39 | 0 | 0 | 0 |
| `compose-polling-nonburnable-materialize-postgres` | 1 | 13 | 0 | 0 | 0 |
| `runtime-web-read` | 6 | 42 | 0 | 0 | 0 |
| `runtime-worker-materialize` | 15 | 116 | 0 | 0 | 0 |

## Failures Encountered & Fixed During Qualification

1. `PotBalanceIncrementalPostgresTest.emptyDeltaReuseProofNoFalsePositivesAndMicrometerObservability`: corrected `UpdateShareholderDetails` constructor argument (`List<ShareholderTransitions.DetailsIntent>`) and effectively-final loop variable `version`.
2. `PotVersionAndBalanceJointIncrementalPostgresTest`: updated v9 (`UpdateShareholderDetails`) path assertion from `incremental/incremental_hit` to `reuse/empty_delta_reuse` (confirming ARB-3 activation with 0 expenses read).

## Demonstrated Invariants

- Formal ARB-3 `EMPTY_DELTA_REUSE` proof verified across `UpdatePotDetails`, `UpdateShareholderDetails`, `UpdateExpenseDetails` (consecutive and `N-m` with `m > 1`), matching full `PotBalanceCalculator` oracle with `0` new physical content rows.
- Zero false positives demonstrated on PostgreSQL 17:
  - Weight modified then reverted in `(K, N]` -> normal A4 `INCREMENTAL` + physical deduplication.
  - Expense created then deleted in `(K, N]` -> normal A4 `INCREMENTAL` + physical deduplication.
  - Zero-amount expense (`amount = 0`) -> normal A4 `INCREMENTAL` + physical deduplication.
  - `DeleteShareholder` -> `FULL / SHAREHOLDER_DELETED` with balance content reused and aggregate content inserted.
  - `emptyDeltaReuseEnabled = false` -> cleanly falls back to normal A4 `INCREMENTAL`.
- Replay idempotence, out-of-order `N-m`, concurrency, transactional fencing (`requireVersionHashesStored`), and `prepareRecovery` on full oracle preserved.
- Micrometer observability distinguishes `EMPTY_DELTA_REUSE`, A4 `INCREMENTAL`, `FULL` fallbacks, inserted vs reused physical content counts, persistence latency, and compute/encoding duration.
