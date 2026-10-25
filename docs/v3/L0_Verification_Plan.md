# V3-6 — L0 foundation verification plan

Reference: `4405b9c338ab8d5e9a3d27be33ccea06bd1daff2`, branch `feat/v3-architecture`.
Implementation authorized by the project owner after the read-only L0-A inventory.
The implementation checkout was detached during implementation and verification. Commit and push to `feat/v3-architecture` were subsequently authorized by the project owner on 2026-10-25; the original checkout remains untouched.

## Scope declared before implementation

- Expected production impact: NONE. Existing production transaction classes are exercised unchanged.
- Primary slice: FOUNDATION, anchor `infra-tx-spring`; external dependencies added in test scope only.
- Secondary production slices: NONE (WEB/COMMAND/EVENT/PROJECTION/BINDING/LKV unchanged).
- Forbidden impact: Pot/Identity domain, Consumption implementation, production DDL, HTTP, Bruno and k6.
- Local proof: `./mvnw -pl infra-tx-spring -am test` from `app/`.
- Global architecture gate: REQUIRED, because this step introduces forbidden-dependency guards and test dependencies.
- Global command: `./mvnw -pl architecture-tests -am test` from `app/`.
- Full reactor: NOT REQUIRED for this bounded, test-only foundation step. Required later at applicable major-wave/main integration gates.
- Database verification: isolated CURRENT **test fixture** only in the local proof. No V2 equivalence/baseline claim.
- Historical chain: not loaded by FOUNDATION. The unfiltered global gate executes existing upstream historical tests; this cost is explicit, with no trusted V2 baseline assumed.
- Escalation: any production edit, new internal Maven edge, real domain object or changed transaction behavior requires a revised declaration before work continues.

## Steps and evidence

| Step | Deliverable | Oracle |
|---|---|---|
| L0-A | [Inventory](L0_Technical_Inventory.md) | Observations separated from reuse recommendations |
| L0-B | `V3FoundationArchitectureTest` | Pure domains, inward application/ports, nonempty imports, positive/negative fixtures |
| L0-C | `V3SchemaIsolationPostgresTest` | PostgreSQL 17, explicit Flyway location, independent schema histories, restart/validation, no V2 objects |
| L0-D | `V3TransactionFoundationPostgresTest`, `V3HistoricalIntervalPostgresTest` | Shared physical JPA/JDBC identity, independent observers, commit/rollback, deferred failure, exact semi-open intervals, anomalies |
| L0-E | `V3FoundationCompositionTest` | Real runner wired by Spring; ambiguous managers refused; explicit WRITE/READ runners remain independent |
| L0-F | `verify-v3-l0.sh`, `summarize-v3.py`, `L0_Proof_Manifest.json` | Fresh raw reports, required methods, skips, SHA/source fingerprints, reproducible commands and bounded verdict |

## Isolation decisions

Use the existing `infra-tx-spring` module; no new production module or production package.
Each PostgreSQL test creates random `v3_write_*` / `v3_read_*` schemas inside a disposable Testcontainers database.
Each realm has its own datasource, EntityManagerFactory and JpaTransactionManager. These are test composition choices, not a deployment prescription.
Flyway uses only `classpath:db/v3-foundation/migration`. Hibernate validates a single generic fixture entity; it never creates tables.
No Spring Boot application/component scan, worker, legacy migrator, `clean()` or deletion of public/V2 schemas is used.
The generic revision table is a test fixture, not the future Pot persistence model. Overlap/gap detection is exercised explicitly; its checks/indexes alone do not claim complete temporal integrity.

## Limits

L0 does not prove DOM-01/02/03, EVT-01/02/03, INV-01, full CON-03/04/06, READ/security, rebuild or performance compliance.
Architecture checks on the existing production bytecode preserve useful direction rules; they do not certify an absent V3 implementation.
The complete suite inventories and future contracts in SPEC-10 remain authoritative. Historical PASS results are not V3 results.
Unknown commit/takeover/sequence proofs must be repeated on their actual V3 components in later steps.
No functional API is added: nothing new is testable via Bruno/k6 after this lot.

## Reproduction

From the repository root, with JDK 21, Python 3 and a working Docker daemon:

```sh
scripts/testing/verify-v3-l0.sh foundation
scripts/testing/verify-v3-l0.sh architecture
scripts/testing/verify-v3-l0.sh architecture-focus # feedback only, not the global closure gate
python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py
```

Each Java gate creates a new evidence directory beneath `app/infra-tx-spring/target/v3-l0-evidence/`.
The manifest distinguishes foundation and architecture coverage; neither verdict is a certification of V3.
The optional architecture-focus command selects only the new L0 rules for feedback;
its missing-test guard is enforced by the collector's required-method manifest.
It never replaces the unfiltered global command. Any historical optional skip remains
visible and makes the all-tests coverage verdict INCONCLUSIVE, even if Maven succeeds
and all mandatory L0 proof methods pass.
