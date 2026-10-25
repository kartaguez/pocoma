# V3-6 — L0 execution report

Date: 2026-10-24. Scope: technical foundation L0-A through L0-F authorized by the project owner.

## Result

The bounded L0 implementation is complete: inventory, architecture guards, isolated PostgreSQL/Flyway fixtures, transaction/history/composition tests and evidence collection.
All mandatory L0 Java proof methods passed (19 methods: 15 PostgreSQL/composition and 4 architecture), together with 7 Python collector tests.
No Pot, Identity, Consumption engine, production migration, endpoint, Bruno scenario or k6 scenario was implemented.
This result is **not V3 functional certification or activation**. No historical PASS was transferred to a V3 requirement.

The unfiltered global architecture command completed with Maven **BUILD SUCCESS** (exit 0), 1,407 tests declared, 0 failures, 0 errors, 1 skip.
The collector returned exit 2 / **INCONCLUSIVE** for complete global coverage because it preserves that skip.
Its per-proof verdicts for L0-B, L0-C, L0-D-TX, L0-D-HISTORY and L0-E are all PASS.
These two verdict levels are deliberately distinct; there is no claim that all 1,407 tests executed successfully.

## Independent review and documentary closure — 2026-10-25

Reviewed L0 delivery: `7c8470a568ece88c4e6652d080dfb026f57681e1`, published on `feat/v3-architecture`.
**Verdict: L0 acceptable pour la poursuite, sans défaut bloquant démontré.**
The independent review was static and documentary; it did not reproduce the PostgreSQL tests.
It accepts the bounded foundation for continuation, with the reservations below, without requiring a rewrite of L0.

The recorded results remain distinct: **19 mandatory Java L0 methods PASS** (15 PostgreSQL/composition, 4 ArchUnit), **7 collector tests PASS**, global Maven gate **BUILD SUCCESS**, but complete global coverage **INCONCLUSIVE** because of the historical exploratory skip identified below.
There is **no V3 functional certification**. This closure records the review outcome only: no new proof result, test rerun or retrospective change to the [proof manifest](L0_Proof_Manifest.json) or raw test reports.

| Reservation | Disposition and deadline |
|---|---|
| I-01 | The V2 inventory is sufficient for L0, but not exhaustive for V3 migrations. Complete targeted impact analyses at the start of each affected lot; no new general inventory. |
| A-01 | ArchUnit coverage of application ports is incomplete, notably `engine.*.port.*`. Strengthen it during L1-A, before that lot closes. |
| A-02 | Complete automatic verification that the tested code matches the published commit is absent. Resolve before L1 closes; existing fingerprints alone do not close this reservation. |

L0 is documentarily closed as acceptable for continuation; these follow-up obligations remain open.
L1 (Pot domain under [SPEC-01](SPEC-01_Pot_Domain.md)) is **not implemented**. Its L1-A through L1-E realization plan and acquired technical choices are tracked in the [canon, section 11](V3_Canon_and_Roadmap.md#11-v3-6--passage-vers-l1).
The SPEC remain the sole functional normative authority; applicable proofs remain due under [SPEC-10](SPEC-10_Test_Certification.md).

## Git and production impact

- Reference / implementation start HEAD: `4405b9c338ab8d5e9a3d27be33ccea06bd1daff2`.
- Implementation checkout: `/private/tmp/pocoma-v3-l0`, initially detached HEAD at that reference.
- At verification completion: HEAD unchanged, no commit or push. The project owner subsequently authorized committing and pushing this L0 delivery to `feat/v3-architecture` on 2026-10-25.
- Original checkout: `/Users/julien.guezennec/Dev/projects/pocoma`, branch `feat/runtime-monolith`, HEAD `90a3e9bc1a1fdd325d0c36e543824239cd85a431`; its pre-existing file changes were preserved.
- The reference commit was fetched and a detached worktree created to avoid editing either existing checkout.
- Expected production impact: NONE. Observed production impact: NONE; no `src/main` file changed.
- Added Maven libraries are external **test-scoped** dependencies in `infra-tx-spring`. No internal module edge or module was added.
- At verification time, implementation files were uncommitted, including new untracked test/document/script files; the archived source manifests identify the tested content independently of its subsequent commit.

Publication changes only this Git bookkeeping, the verification plan's publication note and the canon progress entry. No executable source changed after the final verification, so no verification slice was rerun for publication. The existing worktrees and their unrelated changes remain preserved.

## Declared verification scope

Primary slice: **FOUNDATION**, `infra-tx-spring`, documented before implementation in [L0_Verification_Plan.md](L0_Verification_Plan.md) and added to section 34 of the reactor policy.
Secondary production slices: NONE. Unexpected slice crossings: NONE.
WEB/COMMAND/EVENT/PROJECTION/BINDING/LKV code is unchanged; their upstream tests run as part of the global command's Maven closure.

Global architecture gate: REQUIRED and RUN, because this step adds forbidden-dependency guards and test dependencies.
Full reactor command `./mvnw test`: NOT RUN / NOT REQUIRED for this bounded test-only step. No production graph restructuring occurred; later major-wave/main gates remain required.

Database proof: PostgreSQL 17.11 via Testcontainers (`postgres:17-alpine`), Docker 29.8.2, JDK 21.
FOUNDATION loads only `classpath:db/v3-foundation/migration`, in fresh random WRITE/READ schemas in disposable containers.
The global gate additionally ran existing V2 migration tests (PRIMARY V1–V31 / READ V1–V14); this was declared in advance.
No trusted V2 baseline was assumed or certified. No external/deployed database was migrated.

## Delivered files and traceability

| Step | Deliverable | Evidence / limits |
|---|---|---|
| L0-A | [Inventory](L0_Technical_Inventory.md), [complete Maven graph](L0_Maven_Graph.json) | Reference observations and REUSE/ADAPT/RETIRE/DEFER; SPEC remain normative |
| L0-B | `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3FoundationArchitectureTest.java` and `fixture/` | Nonempty production import, pure domains (including SQL rejection), inward application/ports, positive and forbidden fixtures |
| L0-C | `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/V3SchemaIsolationPostgresTest.java`, `PostgresFoundationTestSupport.java` | Explicit location, independent histories, PostgreSQL version, exact catalog, validation/restart; no V2 bootstrap |
| L0-D | Same package: `V3TransactionFoundationPostgresTest.java`, `V3HistoricalIntervalPostgresTest.java` | PID/XID identity, independent observers, rollback in both JPA/JDBC orders, caught REQUIRED failure, deferred constraint, post-commit observer error, deterministic lock wait, historical boundaries/anomalies |
| L0-D fixture | `app/infra-tx-spring/src/test/resources/db/v3-foundation/migration/V1__foundation_fixture.sql`; test entity `v3/fixture/FoundationEffect.java` | Generic technical rows; no Pot persistence contract or production DDL |
| L0-E | Same package: `V3FoundationCompositionTest.java` | Actual existing runner configuration, ambiguous manager refusal, explicit independent authorities; no application/worker bootstrap |
| L0-F | `scripts/testing/verify-v3-l0.sh`, `summarize-v3.py`, `test_summarize_v3.py`, [proof manifest](L0_Proof_Manifest.json) | Fresh XML, exact expected methods, skips/errors/duplicates, source/environment fingerprints, archived raw artifacts and verdict by proof |

The original 4 H2 tests of SpringTransactionRunner remain supplementary; they are not counted among the 15 new PostgreSQL/composition proofs.
Fixtures do not certify real V3 writer poisoning, slot fencing, unknown commits, event sequencing, security or reconstruction. Those proofs remain due on the actual implementations under SPEC-10.

## Commands actually executed and attempts

Commands below use `app/` as cwd for direct Maven commands and the repository root for scripts/Python.

| Command | Outcome |
|---|---|
| `./mvnw -pl infra-tx-spring -am test` | Initial failure: the isolation test counted Flyway's schema-creation marker as a versioned migration. Assertion corrected to count versioned migrations; later runs pass |
| `scripts/testing/verify-v3-l0.sh foundation` | Underlying canonical command above; PASS, 19 tests, 0 failure/error/skip |
| `scripts/testing/verify-v3-l0.sh architecture` (first attempt) | Upstream tests ran; new ArchUnit rule failed compilation on an unsupported fluent method. Corrected using CompositeArchRule; failure evidence retained |
| `scripts/testing/verify-v3-l0.sh architecture-focus` | `./mvnw -pl architecture-tests -am test -Dtest=V3FoundationArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`; PASS, 4 tests, no skip. Feedback only, not a global gate substitute |
| `scripts/testing/verify-v3-l0.sh architecture` (final) | `./mvnw -pl architecture-tests -am test`; Maven exit 0, 1,407 tests, 0 failure/error, 1 skip; collector exit 2 / INCONCLUSIVE global coverage, all mandatory L0 proofs PASS |
| `python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py` | Final 7 tests PASS; initial collector unpacking typo corrected before the archived gates |
| `git diff --check`, `bash -n scripts/testing/verify-v3-l0.sh`, in-memory Python compile/JSON/whitespace checks | PASS; new untracked files included in the whitespace check |

No Bruno, k6, full-reactor or deployment command was executed.

## Raw evidence

Evidence root: `app/infra-tx-spring/target/v3-l0-evidence/` in this checkout.
These generated files are ignored by Git; copy/archive that directory before running Maven clean if durable off-checkout retention is needed.

| Run | Result |
|---|---|
| `1049760d-c3e7-4c8a-b5aa-fd07ee8b7a71/result.json` | Local FOUNDATION PASS |
| `dc9e1e74-c4c5-4052-9f4a-148fd2d1d898/result.json` | First global attempt FAIL (compilation), retained |
| `ade95e01-c96d-4942-a858-251ea9e8a4dd/result.json` | Focused L0 architecture PASS |
| `a1d35fff-4866-4270-8d7f-7bbededdb306/result.json` | Final global coverage INCONCLUSIVE; required L0 methods PASS |
| `collector-tests.log`, `initial-foundation.log` | Final Python tests and initial local failure log |

Each Java run contains the exact command, HEAD and working-tree state, tool versions, proof manifest, SHA-256 source manifest, Maven log, fresh raw XML and artifact hashes.
The final global run archived 261 fresh XML reports; all listed artifact hashes were independently rechecked successfully.
This report and the canon progress entry were finalized after testing; no test or production code changed afterward.

## Explicit residual observations

The single skip is the pre-existing optional exploratory measurement:

`app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/TerminalSequencingPostgresTest.java`

Method: `zExploratoryContentionSeparateFromCorrectness`; reason: system property `step3b.measure` absent.
It is outside the mandatory L0 proof manifest and is not promoted to a PASS. No performance campaign was added to eliminate an optional historical skip.
The global log also retains a Surefire diagnostic about delayed fork shutdown; the Maven process and all JUnit correctness suites nevertheless completed without failure/error.

Next step: L1, adaptation of the Pot domain under SPEC-01 with a fresh scope declaration. L0 does not authorize or silently implement that work.
