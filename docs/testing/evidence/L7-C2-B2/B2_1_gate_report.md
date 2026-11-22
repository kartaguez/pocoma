# Pocoma V3 — L7-C2-B2.1 Gate Report

- **Date**: 2026-11-19
- **Branch**: `feat/v3-l7`
- **Start HEAD**: `ab3a3cb4f77065f3b2c321aa99e26be371da9a8a`
- **Verdict**: `L7_C2_B2_1_QUALIFIED`

## Verification Scope (`Reactor_Verification_Policy.md` §30)

- **Expected production impact**: `domain-projection` (new package `com.kartaguez.pocoma.domain.projection.balance`). Candidate DDL `V4__pot_balance_shared_storage.sql` in `infra-postgres-read/src/test/resources/db/read-candidate/migration/` only.
- **Observed production impact**: `domain-projection` only (`PotBalanceContent`, `PotBalanceAggregateContent`, `PotBalanceBinaryCodec`, `PotBalanceAggregateBinaryCodec`). Zero modifications to `src/main/resources/db/read/migration/`, `radix/*`, `JdbcPotBalanceStore`, `MaterializePotBalance`, `ReadPot`, `BootstrapGuard`, `ReadinessOracle`.
- **Declared slices**: Primary `PROJECTION` (`runtime-worker-materialize -am`); inner loop `domain-projection,infra-postgres-read -am`. Secondary slices: `NONE`.
- **Verification commands executed**:
  1. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl domain-projection,infra-postgres-read -am test` — **BUILD SUCCESS** (14 modules, 0 failures, 0 errors, 0 skipped; 42.1 s)
  2. `cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am test` — **BUILD SUCCESS** (35 modules, 0 failures, 0 errors, 0 skipped; 27:13 min)
- **Unexpected slice crossings**: `NONE`
- **Global architecture gate**: `NOT REQUIRED`
- **Full reactor**: `NOT REQUIRED` (deferred to B2.4)
- **Database verification**: `CURRENT` (`V1 -> V2 -> V3 -> V4` candidate on PostgreSQL 17.11 Testcontainers)

## Test Results Summary

| Module | Suite | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|
| `domain-projection` | `PotBalanceBinaryCodecTest` | 5 | 0 | 0 | 0 |
| `domain-projection` | `PotBalanceAggregateBinaryCodecTest` | 3 | 0 | 0 | 0 |
| `domain-projection` | *Module total (7 suites)* | 33 | 0 | 0 | 0 |
| `infra-postgres-read` | `PotBalanceSharedSchemaPostgresTest` | 3 | 0 | 0 | 0 |
| `infra-postgres-read` | *Module total (13 suites)* | 39 | 0 | 0 | 0 |
| `runtime-worker-materialize` | *Module total (15 suites)* | 115 | 0 | 0 | 0 |

## Demonstrated Invariants

- Exact bijection between `Fraction` (`BigInteger`) and canonical binary payload (`schema_version = 1`), preserving irreducible fractions, `0/1`, negative sign boundaries, and `10^45+` values.
- Order-independent deterministic encoding and SHA-256 hashing (`SubobjectKey.compareUuid` unsigned 128-bit order and `BucketKey.compareTo`).
- Strict decoding rejecting truncation, trailing bytes, non-minimal `BigInteger`, overlong varint32, non-reduced fractions, unbalanced vectors, invalid `deleted` flags, out-of-bounds/unsorted indices, conservation violations, and hash mismatches.
- Domain separation between `pocoma-bal-v1\0` and `pocoma-bagg-v1\0`.
- Byte-for-byte divergence detection (`exactContentEquals`).
- Candidate `V4__pot_balance_shared_storage.sql` verified on PostgreSQL 17: empty-table guard (`23514`), FK integrity (`23503`), check constraints (`23514`), 8 `ENABLE ALWAYS` immutability triggers (`23514` even as admin), minimal runtime privileges (`SELECT, INSERT` only, `42501` on mutations, 0 `PUBLIC` ACL), 4 PK indexes / 0 secondary indexes.
