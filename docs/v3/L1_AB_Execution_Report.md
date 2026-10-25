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
