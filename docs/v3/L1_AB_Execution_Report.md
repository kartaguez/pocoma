# L1-A / L1-B — Implementation and verification record

Reference: `8221692287420cd69feebd170df7c9ea6c279fdd`. Authorization: implementation A then B only, following independent approval of the published design plan. Independent implementation audit remains required; C is not authorized.

## Declared verification scope (before implementation)

A: production `domain-pot`; tests `domain-pot`, `architecture-tests`. Primary COMMAND, secondary PROJECTION. Feedback `./mvnw -pl domain-pot -am test`; closure `./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test`; global architecture `./mvnw -pl architecture-tests -am test` REQUIRED for A-01. All from `app/`. FULL NOT REQUIRED at A/B checkpoint. Business DB NONE for A. Transitive PostgreSQL/V2 history tests in closure gates are recorded separately. No production POM/engine/infra/runtime/DDL changes permitted in A.

B (not started): application persistence ports and `infra-persistence-primary-jpa`. Primary COMMAND, secondary PROJECTION. Feedback `./mvnw -pl infra-persistence-primary-jpa -am test`; same consumer and architecture closure gates. CURRENT fresh V3-Pot schema, explicit Flyway `classpath:db/v3-pot/migration`; no separate historical migration gate, no V2 trusted baseline assumed. Forbidden: READ/HTTP/Identity/Consumption production, transaction orchestration C. Escalate before changing another boundary.

## Worktree preservation

The original checkout is on `feat/runtime-monolith` at `90a3e9bc` with tracked and untracked local work. `/private/tmp/pocoma-v3-cleanup` owns `feat/v3-architecture` at `ddca2827` with staged documentary changes. Both remain untouched. Implementation uses detached `/private/tmp/pocoma-v3-l1-ab` at the approved reference, equal to the remote branch checked by `git ls-remote` before modification. Publication will explicitly fast-forward the remote ref without moving the other worktree's local branch.

## Technical choices / I-01

Reuse existing stable PotId, ShareholderId, ExpenseId, UserId, Label and Name. New `domain.pot.v3` namespace is allowed by plan §15: V2 mutable grouped aggregates and long rationals cannot express V3. V2 writers, long SQL columns, READ projection codecs and HTTP rationals remain historical and need conversion at their respective future boundaries. No compatibility layer or activated second runtime path is added. V3 uses exact BigInteger rationals and strict positive weights. Complete compositions exist for creations/deletions/reconstruction; transitions take selective components and scoped facts. Three transition classes group Pot/Shareholder/Expense responsibilities. `Changed` construction is private and proposals only originate inside domain transitions.

A-01 selects both top-level and engine-nested ports. SQL/JDBC and implementation/framework dependencies are forbidden. The exact legacy application contract exceptions are enumerated in `V3FoundationArchitectureTest.LEGACY_PORT_CONTRACTS`; they are pure context/outcome/cursor/ordering/admission values, retained until those consumers migrate, reviewed at each L1 contract change. No engine implementation package is generally permitted.

A-02 is initiated by `scripts/testing/verify-v3-l1.py`: committed input comparison before/after each gate, strict expected methods, raw fresh XML/log archival and tested/published input correspondence. Full L1 closure and remote correspondence remain due. No PASS is claimed at this preparation stage.

## A technical closure

Code commits: `8a33b2cb` (domain and guards), `dbec5a8e` (Windows wrapper line endings in collector), `23f18a01` (prevent generated Python bytecode from becoming an untracked build input). Exploratory diagnostics are not certification; their logs are retained in the A archive.

| Gate | Tested commit | Actual result | Evidence |
|---|---|---|---|
| G-A feedback | 23f18a01 | 220 tests, 0 failures/errors/skips; PASS | `a-domain-1792927055484795000/summary.json` |
| G-A consumers | 23f18a01 | Maven exit 0; 1190 tests, 0 failures/errors, 1 optional historical skip; global complete coverage INCONCLUSIVE | `a-consumers-1792927068536741000/summary.json` |
| G-ARCH | 23f18a01 | Maven exit 0; 1422 tests, 0 failures/errors, 1 optional historical skip; required A+ArchUnit methods PASS; global coverage INCONCLUSIVE | `a-arch-1792927315643647000/summary.json` |

The consumer skip is `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, disabled without `step3b.measure`. This optional V2 measurement is outside the mandatory L1 manifest. It remains a SKIP, and global coverage is not called PASS. Required A method outcomes are checked individually. No historical L0 result is transferred.

### A proof mapping

`PotTransitionsTest` methods `l1a01` through `l1a14` implement the corresponding rows L1A-01 through L1A-14 in plan §10 (full exact method names in the committed proof manifest). Variant assertions are deterministic table/loop assertions, not parameterized JUnit tests. Weight permutations cover both orders and final invalid/stale targets; financial assertions cover equal/stale/created-late/deleted/missing references; ordinary attachment covers self/creator/third party. L1A-15 maps to all five `V3FoundationArchitectureTest` methods, with nine nested-port fixtures, eight individually forbidden. No memory-only test is presented as CAS, transaction, durable history, or complete balance proof.

**L1-A technical verdict: PASS, bounded to the mandatory domain/architecture methods and executed Maven gates.** A-01 is technically demonstrated by five fresh ArchUnit methods on production and nine nested fixtures. Global complete coverage remains INCONCLUSIVE due to the single optional historical measurement, with no gate failure/error. No business PostgreSQL proof in A, no unexpected production slice crossing, no full reactor (checkpoint A/B; deferred to E). I-01 is complete for A's values/compositions and lists V2 consumers still deferred. A-02 initiated, complete L1 closure still due. Independent acceptance is pending.

Raw A evidence: `docs/testing/evidence/L1-AB/A-raw.tar.gz` (all gate directories and exploratory logs, including XML and environment/input manifests). `mandatory-verdict.json` records exact required method outcomes separately from global coverage. Seven fresh collector assessment unit tests passed as supplementary feedback.

**B start declaration:** A PASS at `23f18a01`; B now proceeds under the scope already declared above. JDBC in the existing persistence adapter module is selected for explicit grouped SQL/history control, with constructor injection and caller-owned transactions. No production dependency/POM change, no transaction coordinator or runtime activation. Exact text rationals and stable-identity foreign keys replace incompatible V2 storage.

## B implementation before closure gates

Production impact remains exactly the declared B boundary: three internal persistence port files in `engine-write-pot`, three JDBC implementation/helper files and one fresh migration in `infra-persistence-primary-jpa`. No POM, runtime/composition, READ/HTTP, Identity, generic Consumption or transaction-framework production changed. No unexpected slice crossing. Transaction-manager compatibility is tested through explicit test composition.

See [persistence contract](L1_B_Persistence_Contract.md) for storage, query semantics, transaction preconditions and the C handoff. Review identified a possible delayed write into already committed version metadata; the writer now verifies that the candidate metadata was inserted in its current physical PostgreSQL transaction. A dedicated test exercises the previously unchanged component case, independently of local-version collision. Deferred constraints reject empty versions and incomplete initial components. These are storage-integrity protections; no C CAS/claim/request/event orchestration has been implemented.

The 18 PostgreSQL methods are enumerated before the closure campaign in `L1_Proof_Manifest.json`. Mapping:

| Method prefix | Proof/oracle |
|---|---|
| b01 | Exact fresh schema/catalog, functions/triggers, Flyway restart/validate, no V2 tables |
| b02 | D01-A..L full literal snapshots, random version order seed 731 |
| b03 | Semi-open bounds, only changed components, old rows retained |
| b04 | D01-M Shareholder deletion, exact Header/Details/Weight intervals and old states |
| b05 | D01-K primitive rollback/no-op, no metadata/local revision advance |
| b06 | Selected current/historical facts; absence versus not loaded; user association at V |
| b07 | Exhaustive payer-only / participant-only last reference among 35 prior Expenses; deleted Expenses and old V |
| b08 | Arbitrary BigInteger amount/default/specific weight, 0.125, canonical zero, old V intact |
| b09 | Gap, overlap, missing component, unreduced stored rational rejected |
| b10 | Active uniqueness, syntax/nullity/sign, cross-Pot foreign keys |
| b11 | Repeatable-read snapshot during another physical commit; no sleeps |
| b12 | Constant grouped read count for 1/21 Expenses, one participant query |
| b13 | Mandatory caller transaction and isolation; invalid V rejected |
| b14 | JPA/JDBC physical transaction compatibility, independent observer, rollback |
| b15 | Corrupt references/missing participants cannot become negative usage facts |
| b16 | Last creation fails: first creation, metadata and all components roll back |
| b17 | Deferred integrity: SQLSTATE 23514 for empty version/incomplete creation |
| b18 | Already committed candidate cannot receive a later proposal on another component |

The last focused diagnostic executed 18 PostgreSQL methods without failures/errors/skips. Diagnostics remain non-certifying and are retained in `B-diagnostics.tar.gz`. Final gates have not yet run on the committed code at this entry. New B Java code was formatted with temporary google-java-format 1.24.0; no project dependency was added.

A-02 collector extended for B: removes generated Maven targets before a gate, checks committed inputs before/after, inventories resulting bytecode/resources, resolved dependency JAR digests, external configuration digests and nonsecret runtime options; archives PostgreSQL tables/catalog/query evidence alongside raw XML/logs. Global versus required-method verdicts remain separate. Complete L1 closure remains due before E finishes.
