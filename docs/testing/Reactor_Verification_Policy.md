Pocoma Reactor Verification Policy

Status: NORMATIVE

Operational commands and risk matrix: [§35 — FAST / CHANGE / INTEGRATION /
CERTIFICATION](#35-operational-verification-and-build-cycle--fast--change--integration--certification).
These levels do not replace the canonical slice/lot gates below or V3 milestones G0–G3.

This document defines how implementation changes in Pocoma are verified.

Its purpose is to preserve strong engineering proof while avoiding the systematic cost of rebuilding and retesting the complete Maven reactor for changes whose expected impact is local.

The central principle is:

Verification scope is part of the design of a change.

A change is not correctly verified merely because a sufficiently large test suite eventually passes. The expected architectural impact MUST be identified before implementation, and verification MUST prove the change at the smallest meaningful architectural boundary.

Unexpected impact outside that boundary is itself an architectural finding.

⸻

1. Objectives

This policy has four objectives:

1. provide fast feedback during implementation;
2. preserve strong non-regression guarantees at appropriate integration gates;
3. make unintended architectural coupling visible;
4. prevent “run the full reactor just to be safe” from becoming the default verification strategy.

This policy does not optimize for the smallest possible number of tests.

It optimizes for the smallest verification scope that proves the property being changed.

⸻

2. Fundamental distinction: local proof vs global proof

Pocoma distinguishes two categories of proof.

2.1 Local / bounded proof

A bounded proof demonstrates correctness within an explicitly identified architectural capability.

It is the default verification mode for an implementation step.

Typical form:

./mvnw -pl <slice-anchor> -am test

The Maven anchor identifies the capability under test.

-am includes the upstream modules required to build and test that capability.

⸻

2.2 Global proof

A global proof demonstrates integration of the complete system or verifies system-wide architectural constraints.

Examples:

./mvnw test

and:

./mvnw -pl architecture-tests -am test

Global proofs are deliberately expensive.

They are gates, not the normal implementation feedback loop.

⸻

3. Verification boundary MUST be declared before implementation

Every non-trivial implementation step MUST declare its expected verification boundary before production code is modified.

The declaration MUST contain:

Verification scope
Expected production impact:
- ...
Primary slice:
- ...
Secondary slices:
- NONE / ...
Forbidden production impact:
- ...
Global architecture gate:
- REQUIRED / NOT REQUIRED
Full reactor:
- REQUIRED / NOT REQUIRED
Database verification:
- ...
Escalation conditions:
- ...

The declaration represents an architectural hypothesis:

this change should be implementable inside these boundaries.

Verification then tests that hypothesis.

The scope MUST NOT be reconstructed after implementation merely to match the files that happened to change.

⸻

4. Pocoma canonical reactor slices

The current Pocoma Maven structure already exposes useful runtime boundaries.

Canonical slices therefore use existing runtime modules as Maven anchors.

No separate Maven aggregators are required merely to implement this policy.

The canonical slices are:

* WEB
* COMMAND
* EVENT
* PROJECTION
* BINDING
* LKV
* FOUNDATION (V3 L0 test infrastructure only; section 34)

A change MAY involve more than one slice.

This must be explicit.

⸻

5. WEB slice

5.1 Anchor

runtime-web-api

5.2 Canonical command

./mvnw -pl runtime-web-api -am test

5.3 Responsibility

WEB proves the externally exposed HTTP application boundary.

Its dependency graph currently includes capabilities such as:

* command admission;
* authentication integration;
* Spring transaction integration;
* persistence needed by the Web runtime;
* command-result reads;
* projection reads;
* read persistence;
* projection JSON schema handling;
* HTTP write command handling;
* HTTP read query handling;
* pot reads;
* observability.

The exact dependency graph remains defined by Maven, not duplicated in this document.

5.4 Typical WEB changes

Examples:

* controller changes;
* HTTP endpoint changes;
* DTO changes;
* request validation;
* response representation;
* HTTP status semantics;
* HTTP authentication wiring;
* mapping between HTTP representations and application contracts.

5.5 Expected isolation

A pure WEB change SHOULD NOT require production changes in:

* command consumption runtime;
* event consumption runtime;
* projection-task consumption runtime;
* binding consumption runtime;
* LKV consumption runtime.

If such a change becomes necessary, the WEB boundary has been crossed.

The implementation MUST NOT silently expand its scope.

The crossing must first be explained.

⸻

6. COMMAND slice

6.1 Anchor

runtime-command-consumption-worker

6.2 Canonical command

./mvnw -pl runtime-command-consumption-worker -am test

6.3 Responsibility

COMMAND proves the asynchronous command execution capability.

It includes the modules required by Maven for command consumption, including the relevant:

* command locator;
* consumption worker infrastructure;
* command execution orchestration;
* persistence;
* transaction handling;
* Pot command binding;
* command-result production;
* authorization/binding dependencies required during execution.

6.4 Typical COMMAND changes

Examples:

* command claiming;
* command eligibility;
* retry semantics;
* command execution;
* transactional command processing;
* business mutation;
* Event append performed by command execution;
* command outcome;
* resulting version;
* terminal command state;
* command authorization during execution;
* binding fencing used by command execution.

6.5 Cross-slice warning

A command may consume information owned by another capability without making the complete owner runtime part of COMMAND.

For example, command execution may depend on binding contracts or persistence primitives.

That does not automatically mean the complete BINDING runtime must be tested.

The BINDING slice becomes a secondary slice only when BINDING production behavior itself changes.

⸻

7. EVENT slice

7.1 Anchor

runtime-event-consumption-worker

7.2 Canonical command

./mvnw -pl runtime-event-consumption-worker -am test

7.3 Responsibility

EVENT proves processing originating from durable Events.

Typical responsibilities include:

* Event discovery;
* Event claiming;
* Event processing;
* creation of downstream work;
* interactions with ProjectionTask scheduling;
* Event-driven command-result behavior where applicable.

7.4 Typical EVENT changes

Examples:

* Event eligibility;
* Event consumer semantics;
* mapping Event → downstream task;
* Event processing retries;
* scheduling behavior initiated from Events;
* Event processing transaction semantics.

7.5 Expected isolation

Changes to EVENT SHOULD NOT automatically require:

* WEB;
* LKV;
* BINDING runtime;
* COMMAND runtime.

Shared contracts may justify additional slices, but this must be declared.

⸻

8. PROJECTION slice

8.1 Anchor

runtime-task-consumption-worker

8.2 Canonical command

./mvnw -pl runtime-task-consumption-worker -am test

8.3 Responsibility

PROJECTION proves ProjectionTask execution and projection materialization.

The current runtime dependency graph includes capabilities related to:

* ProjectionTask execution;
* projection persistence;
* read persistence;
* generic consumption;
* projection orchestration;
* Pot projection;
* Balance projection;
* Command Result projection;
* projection JSON schema handling.

8.4 Typical PROJECTION changes

Examples:

* ProjectionTask execution;
* task eligibility;
* projection materialization;
* projection artifact publication;
* READ_POT;
* BALANCES;
* COMMAND_RESULT;
* projection failure handling;
* projection version continuity;
* projection JSON contract.

8.5 Projection-specific narrowing

When practical, implementation feedback MAY initially run tests only for the directly modified projection modules.

However, closure of a projection-related step SHOULD use the canonical PROJECTION slice when the runtime materialization path is affected.

⸻

9. BINDING slice

9.1 Anchor

runtime-binding-consumption-worker

9.2 Canonical command

./mvnw -pl runtime-binding-consumption-worker -am test

9.3 Responsibility

BINDING proves the asynchronous external-identity/binding lifecycle capability.

It includes the binding locator and the persistence/transaction infrastructure required by that runtime.

Typical concepts owned or materially governed by this capability include:

* external identity binding;
* binding lifecycle;
* BindingId;
* binding revisions;
* binding facts;
* attach/detach processing;
* binding stream continuity;
* binding-derived current state.

9.4 Important distinction: BINDING ownership vs binding use

Binding information is also consumed by other slices.

In particular, COMMAND may observe or fence binding state during command execution.

Therefore:

* changing command-side use of an existing binding contract may require COMMAND only;
* changing the binding contract itself generally requires BINDING and every materially affected consumer.

Example:

Change:
fence implementation inside command execution,
without changing the binding persistence contract.
Slices:
COMMAND

versus:

Change:
semantic meaning or persistence contract of binding revision R.
Slices:
BINDING
COMMAND

This distinction prevents every binding-related command change from unnecessarily becoming a system-wide verification.

⸻

10. LKV slice

10.1 Anchor

runtime-latest-known-version-consumption-worker

10.2 Canonical command

./mvnw -pl runtime-latest-known-version-consumption-worker -am test

10.3 Responsibility

LKV proves Last Known Version consumption and persistence behavior.

10.4 Expected isolation

LKV is intentionally a narrow capability.

A change outside LKV SHOULD NOT require production changes to the LKV runtime unless Last Known Version semantics themselves are affected.

Unexpected LKV impact is therefore a particularly strong coupling signal.

⸻

11. Multi-slice changes

A change MAY legitimately cross architectural boundaries.

In that case, do not replace targeted verification with the full reactor.

Explicitly compose the required slices.

Example:

./mvnw \
  -pl runtime-command-consumption-worker,runtime-binding-consumption-worker \
  -am test

Another example:

./mvnw \
  -pl runtime-event-consumption-worker,runtime-task-consumption-worker \
  -am test

The selected slices MUST correspond to actual changed behavior.

“Several things might theoretically be affected” is not sufficient justification.

⸻

12. Use of -am

-am is the standard Pocoma mechanism for slice verification.

Example:

./mvnw -pl runtime-command-consumption-worker -am test

The anchor identifies the capability.

Maven determines the upstream dependency closure required to build it.

This has two important properties:

1. the slice follows the real dependency graph;
2. the slice automatically evolves when legitimate Maven dependencies evolve.

The list of transitive modules SHOULD therefore not be manually duplicated in this policy.

The anchor is normative.

The Maven graph determines its build closure.

⸻

13. Use of -amd

-amd MUST NOT be used as the default mechanism for Pocoma slice verification.

A low-level shared module may have many downstream consumers.

Blind use of:

-amd

can therefore expand a targeted verification into a large fraction of the complete reactor.

When downstream consumers need verification, prefer explicitly selected consumer anchors.

Example:

./mvnw \
  -pl runtime-command-consumption-worker,runtime-web-api \
  -am test

rather than deriving an uncontrolled downstream closure.

-amd MAY be used when a step explicitly intends to verify all downstream consumers and the resulting scope is understood.

⸻

14. Shared-module changes

Not every change originates in a runtime module.

A shared module may be modified directly.

Examples include:

* domain contracts;
* persistence infrastructure;
* transaction infrastructure;
* generic consumption infrastructure;
* projection schemas;
* authentication contracts.

In such cases, verification MUST be selected according to the consumers whose behavior may actually change.

The rule is:

select consumer slices, not merely the shared producer module.

For example, if a shared persistence contract affects COMMAND and BINDING:

./mvnw \
  -pl runtime-command-consumption-worker,runtime-binding-consumption-worker \
  -am test

A shared-module modification does NOT automatically imply FULL.

It implies explicit impact analysis.

⸻

15. Unexpected boundary crossing

During implementation, Codex may discover that production code outside the declared slices must change.

This is not automatically an error.

It is, however, an architectural finding.

Codex MUST:

1. identify the unexpected module;
2. identify the architectural capability it belongs to;
3. explain why the original boundary was insufficient;
4. determine whether:
    * the original impact analysis was incomplete;
    * a shared contract legitimately changed;
    * an undesirable coupling has been discovered;
    * the implementation approach is crossing a boundary unnecessarily;
5. revise the verification scope before continuing.

Codex MUST NOT silently broaden the scope and continue.

⸻

16. Architecture tests

Pocoma currently has a global:

architecture-tests

module.

Its dependency graph spans a large portion of the application, including multiple domains, engines, infrastructure modules and runtimes.

Consequently:

./mvnw -pl architecture-tests -am test

is a global or near-global proof.

It MUST NOT be included automatically in every implementation step.

⸻

17. Architecture verification levels

17.1 Local architecture proof

Architecture or structural tests located naturally inside modules participating in a slice run as part of the normal slice build.

No additional global gate is required.

17.2 Global architecture proof

The architecture-tests module is required when:

* module dependencies are changed;
* an architectural boundary is intentionally changed;
* modules are added, removed, collapsed or moved;
* runtime isolation rules are modified;
* a change explicitly concerns forbidden dependencies;
* a WA/Wave closure requires global structural proof;
* an unexpected cross-slice dependency suggests architectural regression.

Canonical command:

./mvnw -pl architecture-tests -am test

Because this command pulls a large dependency closure, it is intentionally excluded from ordinary inner-loop verification.

⸻

18. Full reactor

Canonical full-reactor proof:

./mvnw test

The full reactor proves complete Maven-level integration.

It MUST NOT be run automatically for every implementation step.

It MUST be run when the applicable integration gate requires it.

⸻

19. Full-reactor gates

The full reactor is normally REQUIRED for:

Major Wave closure

When several related steps have accumulated and the Wave represents a coherent architectural milestone.

Integration into main

Before or as part of integrating a significant development branch into main.

Broad dependency restructuring

When the Maven dependency graph or module boundaries have materially changed.

Cross-cutting infrastructure changes

When a shared infrastructure change has impact that cannot be bounded confidently to a small set of slices.

Explicit global stabilization

When the purpose of the step is to establish a new known-good system baseline.

⸻

20. Full reactor is NOT an automatic escalation

A failing slice test does not imply:

run FULL

The failure must first be classified.

Possible outcomes:

failure belongs to declared slice
→ fix locally
failure reveals missing secondary slice
→ expand declared scope
failure reveals architectural coupling
→ investigate boundary
global invariant may be affected
→ run global gate
uncertainty alone
→ analyze, do not blindly run FULL

⸻

21. Step, WA/Wave and integration verification

Pocoma uses three practical verification horizons.

21.1 STEP

Normal implementation unit.

Expected verification:

selected slice(s)
+
their Maven dependency closure

Normally:

global architecture-tests: NO
full reactor: NO

⸻

21.2 WA / WAVE GATE

Architectural milestone.

Expected verification:

all slices materially affected by the WA/Wave
+
global architecture tests when applicable
+
full reactor when the Wave represents a major integration milestone

A small WA does not necessarily require FULL.

A major Wave normally does.

The Step Plan MUST state which applies.

⸻

21.3 INTEGRATION / MAIN GATE

Expected verification:

FULL REACTOR
+
GLOBAL ARCHITECTURE TESTS
+
applicable database verification

This is where Pocoma deliberately pays the complete integration cost.

⸻

22. Database verification

Database verification follows the same principle as reactor verification:

do not prove the complete historical database evolution when the property under test concerns only the current schema.

Pocoma distinguishes:

* CURRENT schema verification;
* CURRENT BASELINE verification;
* HISTORICAL migration verification.

⸻

23. Current migration state

At the time this policy was introduced, the main persistence migration chain contains versioned migrations through:

V23

The most recent binding-related sequence includes:

V18 — user identity binding authority
V19 — recorded command envelope expansion
V20 — external identity binding lifecycle
V21 — binding occurrences and fact expansion
V22 — binding history and contract repair
V23 — binding fact type contract closure

This makes the schema resulting from V23 a natural candidate for a new current baseline.

It is NOT automatically trusted merely because it is the latest migration.

⸻

24. Database baseline status

Current status:

Candidate baseline:
SCHEMA AFTER V23
Trusted baseline:
NONE

Until an explicit baseline certification step succeeds, the existing migration mechanism remains authoritative.

No migration V1–V23 should be deleted or bypassed merely because this policy exists.

⸻

25. Baseline certification

A database baseline becomes TRUSTED only after an explicit proof compares two independently created databases.

Historical path

empty database
      ↓
V1
      ↓
...
      ↓
V23
      ↓
schema A

Baseline path

candidate BASELINE_V23
      ↓
schema B

The certification must establish the required equivalence between A and B.

At minimum compare:

* schemas;
* tables;
* columns;
* SQL types;
* nullability;
* primary keys;
* foreign keys;
* unique constraints;
* check constraints;
* indexes;
* defaults;
* sequences/identity semantics where relevant;
* other database objects relied upon by Pocoma.

If historical data transformations matter, representative historical data MUST also be tested.

Schema equivalence alone does not prove data migration correctness.

⸻

26. After baseline certification

Once a baseline is explicitly marked TRUSTED, normal current development may use:

TRUSTED BASELINE
       ↓
migrations introduced after baseline
       ↓
current schema
       ↓
targeted tests

The complete historical chain remains a separate proof:

V1
 ↓
...
 ↓
latest

It is no longer part of every current-schema verification.

The policy MUST then be updated with:

Trusted baseline identifier
Baseline schema version
Current bootstrap mechanism
Historical migration mechanism
Baseline equivalence test

⸻

27. Historical migration gate

Full migration-history verification is appropriate when:

* certifying a baseline;
* changing migration infrastructure;
* changing historical upgrade semantics;
* preparing a release where old deployed databases must remain upgradeable;
* explicitly testing historical compatibility.

It is NOT automatically required for:

* HTTP changes;
* pure domain changes;
* projection algorithm changes with no migration;
* command execution changes with no migration;
* ordinary refactoring.

⸻

28. Migration retention

Retaining migration files and executing them on every build are separate decisions.

Old migrations MAY remain in the repository while being removed from the normal current-development bootstrap path after a trusted baseline exists.

Physical deletion or archival of historical migrations is a separate decision.

It requires explicit knowledge of the oldest database version that Pocoma still supports upgrading.

Until then:

preserve history, but do not necessarily replay history everywhere.

⸻

29. Mandatory Step Plan section

Every future Pocoma Step Plan involving implementation MUST contain a section equivalent to:

## Verification scope
Expected production impact:
- ...
Primary slice:
- WEB / COMMAND / EVENT / PROJECTION / BINDING / LKV
Secondary slices:
- NONE / ...
Forbidden production impact:
- ...
Required local proof:
- ...
Global architecture gate:
- REQUIRED / NOT REQUIRED
Full reactor:
- REQUIRED / NOT REQUIRED
Database verification:
- NONE / CURRENT / BASELINE / HISTORICAL
Next mandatory global gate:
- ...
Escalation conditions:
- ...

This section is part of the design review of the step.

⸻

30. Mandatory Codex behavior

Before implementation Codex MUST:

1. read this policy;
2. read the relevant Step Plan/reference documentation;
3. inspect the expected change;
4. state the selected verification scope;
5. identify the primary slice;
6. identify secondary slices, if any;
7. identify forbidden production impact;
8. state whether any global gate is required.

During implementation Codex MUST:

1. remain inside the declared impact boundary when possible;
2. report unexpected boundary crossings;
3. revise the scope explicitly before expanding it;
4. avoid FULL as a generic uncertainty-resolution mechanism.

After implementation Codex MUST report:

Start HEAD:
...
Final commit:
...
Expected production impact:
...
Observed production impact:
...
Declared slices:
...
Verification commands executed:
...
Unexpected slice crossings:
NONE / ...
Global architecture gate:
RUN / NOT REQUIRED
Full reactor:
RUN / NOT REQUIRED
Database verification:
...
Push:
...
Final divergence:
...
Working tree:
...

⸻

31. Examples

Example A — HTTP DTO change

Expected impact:
HTTP representation only
Primary slice:
WEB
Secondary slices:
NONE
Command:
./mvnw -pl runtime-web-api -am test
Forbidden production impact:
COMMAND
EVENT
PROJECTION
BINDING
LKV
architecture-tests:
NOT REQUIRED
FULL:
NOT REQUIRED

If EVENT production code must change, stop and explain why.

⸻

Example B — Command execution algorithm

Primary slice:
COMMAND
Command:
./mvnw -pl runtime-command-consumption-worker -am test
Secondary slices:
NONE unless another capability's production contract changes
FULL:
NOT REQUIRED

⸻

Example C — Binding revision semantics used by Commands

Primary slice:
BINDING
Secondary slice:
COMMAND
Command:
./mvnw \
  -pl runtime-binding-consumption-worker,runtime-command-consumption-worker \
  -am test
architecture-tests:
depends on whether dependency/boundary rules change
FULL:
normally deferred to WA/Wave gate

⸻

Example D — Event scheduling ProjectionTasks

Primary slice:
EVENT
Secondary slice:
PROJECTION
Command:
./mvnw \
  -pl runtime-event-consumption-worker,runtime-task-consumption-worker \
  -am test
FULL:
NOT REQUIRED for ordinary step closure

⸻

Example E — Maven dependency restructuring

Affected slices:
explicitly determine
architecture-tests:
REQUIRED
FULL:
REQUIRED before closure

⸻

Example F — New database migration inside BINDING

Primary slice:
BINDING
Secondary slices:
according to actual consumers affected
Database:
current migration verification
Historical migration chain:
NOT REQUIRED unless historical upgrade semantics are affected
FULL:
according to integration gate

⸻

32. Anti-patterns

The following verification behaviors are prohibited unless explicitly justified.

“Run everything to be safe”

change
→ ./mvnw test

without impact analysis.

This hides architectural uncertainty instead of resolving it.

⸻

Retroactive scope selection

modify many modules
→ see what changed
→ declare those modules to be the expected scope

The scope must be declared before implementation.

⸻

Automatic architecture-tests

Adding:

architecture-tests

to every change defeats targeted verification because its dependency closure is broad.

⸻

Automatic downstream expansion

Using:

-amd

without understanding the resulting consumer graph.

⸻

Silent slice crossing

Changing an unrelated runtime because it happens to make the tests pass.

Unexpected crossings must be reported and understood.

⸻

Confusing compilation closure with architectural impact

-am may compile/test supporting modules.

That does not mean all of those modules are architecturally “changed”.

Reports MUST distinguish:

Observed production impact

from:

Maven build closure

⸻

33. Governing principle

Pocoma verification follows this hierarchy:

LOCAL PROPERTY
      ↓
LOCAL PROOF
CAPABILITY CHANGE
      ↓
SLICE PROOF
CROSS-CAPABILITY CHANGE
      ↓
EXPLICIT MULTI-SLICE PROOF
ARCHITECTURAL MILESTONE
      ↓
GLOBAL ARCHITECTURE PROOF
SYSTEM INTEGRATION
      ↓
FULL REACTOR
HISTORICAL DB COMPATIBILITY
      ↓
HISTORICAL MIGRATION PROOF

The strongest proof is not always the largest proof.

The correct proof is the one whose scope corresponds to the invariant being established.

The full reactor remains authoritative for global integration.

It is no longer the default answer to local uncertainty.

⸻

34. FOUNDATION — bounded V3 L0 test infrastructure

Anchor: infra-tx-spring

Canonical local command (from app/):

./mvnw -pl infra-tx-spring -am test

This slice verifies test-only PostgreSQL/Flyway fixtures, transaction behavior,
generic historical intervals and minimal explicit Spring composition. It does
not implement or certify Pot, Identity, Consumption or production migrations.
Additional PostgreSQL/JPA/Flyway dependencies MUST remain in test scope.
Its Flyway bootstrap is restricted to classpath:db/v3-foundation/migration;
this is a fresh fixture, not a trusted baseline or bypass of V2 migrations.

Production changes to a shared transaction component still require the affected
consumer slices under section 14. FOUNDATION alone is insufficient for those changes.
Changes to forbidden-dependency guards or module dependencies require the
canonical global architecture gate under section 17. That unfiltered gate still
runs existing upstream tests, including historical migrations; do not describe it
as an isolated L0 database proof. The existing V2 trusted-baseline status is unchanged.
Test-only L0 additions without production graph restructuring do not require FULL;
the later integration/major-wave gates remain applicable.

See docs/v3/L0_Verification_Plan.md and docs/v3/L0_Proof_Manifest.json for
the bounded proof inventory, commands and collection contract.

⸻

## 34. Proportionate certification and evidence reuse

Procedural clarification adopted for future campaigns, 2026-10-29. Sections
3–33, applicable lot contracts and SPEC-10 remain authoritative. This section
does not remove an oracle, a required fresh execution or a contractual gate.
Historical execution commands and verdicts are never rewritten.

| Change category | Minimum verification | Conditions requiring a wider gate |
|---|---|---|
| Documentation / evidence encoding only | Changed links/anchors, manifest paths and SHA-256, lossless restoration where relevant; script checks if tooling changes. Maven slices NONE. | An executable input, oracle, dependency or application contract actually changes: redeclare the corresponding row before implementation. A normative relaxation requires explicit human arbitration. |
| Tests or collection scripts only | Execute the changed tooling's meaningful positive/negative checks; for changed application tests, prove the required methods on their actual module/consumer slice and preserve their independent oracles. | New dependency/boundary/forbidden-dependency rule: §17 global architecture gate. Lot closure still requires its specified unfiltered consumer gate; a filtered run is feedback, not a substitute. |
| Internal to one slice | Its canonical consumer anchor, `./mvnw -pl <anchor> -am test`, with required methods, outcomes and skips recorded. | Changed shared contract or consumer behavior: explicitly add affected consumer anchors. Global triggers remain §§17/19; uncertainty alone does not trigger FULL. |
| Transactional / SQL | Canonical affected consumer slice(s), plus real PostgreSQL protocols for changed atomicity, locking, fencing, commit/rollback/recovery hypotheses and physical authorities. | Cross-authority/owner/routing contract: affected consumers plus architecture gate when §17 applies. Historical migration proof only under §27; current bootstrap may use a trusted baseline only after §§24–26 certification. Trusted baseline currently NONE. |
| Architectural boundary | All materially affected canonical consumer slices plus `./mvnw -pl architecture-tests -am test`. | Broad Maven/module restructuring or unbounded cross-cutting infrastructure impact: full reactor under §19. |
| Lot closure | The lot's declared unfiltered slices and required PostgreSQL variants, provenance and closure/audit evidence; reused observations explicitly distinguished from newly executed results. | Architectural milestone: §21.2 and its explicit plan. Major Wave normally requires `./mvnw test`; no automatic FULL for every small lot or documentary human acceptance. An explicit lot gate is not silently waived. |
| Release certification / significant main integration | `./mvnw test`, global architecture gate, applicable database upgrade/current-schema proof and all release-specific SPEC-10 protocols, with an explicit scope and open reserves. | Deployment/environment/scale assumptions not covered require additional relevant qualification. A local PASS never certifies absent business variants or production readiness. |

Commands run from `app/`. `<anchor>` means one of the canonical anchors in
§§5–10, not a new aggregator. Direct fixture/module proofs remain bounded
contributions, not additional canonical runtime slices.

When several declared slices share upstream modules, prefer one composed
invocation, for example:

```
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker -am test
```

Maven builds/tests a shared module once within that invocation. This is allowed
only when each anchor is included, the required methods/variants and physical
proofs are present, and the contract does not require independent isolated
executions. Never combine work against shared mutable targets or reports from
concurrent gates. Do not infer architectural isolation from a composed gate.
Do not use `-DskipTests`, `-amd`, optional-test masking or a stale XML set to
manufacture a saving. Preserve FAIL, SKIP, NOT RUN, UNKNOWN and INCONCLUSIVE.

PostgreSQL concurrency campaigns are selected by the affected contract and its
actual WRITE/READ authorities: an SQL/transaction/fencing/renewal/oracle change
requires the applicable interleavings, including adverse outcomes. A document or
lossless encoding change does not require replaying historical concurrency
campaigns. A required unchanged test may still run transitively in a canonical
unfiltered Maven closure; this section does not bypass such tests.

Historical proof reuse requires a recorded tested SHA, accessible and verified
artifacts, an explicit old→new input/hypothesis comparison (including semantic
configuration, SQL, dependencies and relevant toolchain/PG environment), the
original oracle and all limits. Changed or unverified assumptions prohibit
reuse for that property. Reuse is an attribution to the old SHA, never a fresh
execution, and cannot satisfy a specifically required fresh release/lot gate.

Evidence retention, byte-preserving storage aliases and the I5 human acceptance
are documented in [the rationalization dossier](Certification_Evidence_Rationalization.md).
Any future change to a SPEC-10 requirement, oracle, threshold, variant allocation
or explicit mandatory gate is normative and requires separate human arbitration.

⸻

## 35. Operational verification and build cycle — FAST / CHANGE / INTEGRATION / CERTIFICATION

Adopted for future development on 2026-10-31. These are **operational levels**,
not the normative V3 milestones G0/G1/G2/G3. SPEC-10, the lot contracts and the
unfiltered commands above retain their authority. Historical acceptance and FAILs
are unchanged. A filtered operational PASS cannot satisfy an explicitly required
unfiltered lot/architecture/release gate.

### Declare the change before selecting feedback

Record modified modules, affected contracts/risks, primary and secondary slices,
required level, selected campaigns, deferred campaigns and reasons. Choose risks
from Domain/Pot, Identity/Invitation, Security/Authorization, Transaction/Atomicity,
Events/Publication, Consumption/Discovery, Projection/READ, Runtime/Assembly,
Architecture, Concurrency/Recovery and Performance/Capacity. Source review and the
actual physical authority determine coverage; a module or class name alone does not.

| Level | Trigger and build strategy | Proof and indicative budget |
|---|---|---|
| FAST | Significant local iteration; targeted incremental `test`, no automatic `clean`, `install` or packaging. | Reviewed pure domain/application invariants and applicable cheap local structural checks; aim 1–3 min. Heavy PG/contention/capacity is deferred explicitly. |
| CHANGE | Before significant commit/delivery; compile changed scope and upstream dependencies in one reactor. Add consumers when APIs/shared contracts change. | FAST plus affected canonical slice, required PG/security/atomicity and direct interaction proofs; aim 3–8 min where possible. A reviewed filtered recipe is bounded feedback, not the canonical unfiltered slice gate. |
| INTEGRATION | Shared infrastructure, transaction boundaries, substantial refactoring, multiple modules or runtime assembly. | Deeper affected PG/interleaving/recovery/contract proofs; complete global architecture gate when §17 requires it. Aim 10–20 min where possible; never remove an oracle to fit a budget. |
| CERTIFICATION | Required lot closure, major milestone, release or explicit global decision. | Unfiltered full reactor plus all applicable SPEC-10/PG/concurrency/recovery/capacity and release/distribution proofs. Includes quarantined scenarios. `clean install` remains due wherever the applicable contract requires it. No local budget caps this gate. |

Observed Identity recipe Maven process times on 2026-10-31: FAST **6.686 s**
(65 tests), CHANGE **144.100 s** (127), INTEGRATION **216.263 s** (162), all without
failure/error/skip. These are different scopes, not equal-coverage speedups;
collector preparation/archiving is outside these timings. The full diagnostic
`clean install` took **3079.751 s** and failed on `i5_r12` (1867 tests, one failure,
one skip). [Measurements and limits](Verification_Optimization_Report.md).

The current source-reviewed Identity recipe is centralized in
[`verification-campaigns.json`](../../scripts/testing/verification-campaigns.json).
It is intentionally narrow. It does not auto-classify an arbitrary diff or claim
coverage of future Identity/Pot/invitation features. Add a test to the appropriate
recipe when adding an invariant; review the resulting plan before execution.
Unknown scopes and missing/ambiguous sources fail closed.

Commands from repository root (Maven commands execute inside `app/`):

```sh
python3 -B scripts/testing/verify.py FAST identity
python3 -B scripts/testing/verify.py CHANGE identity
python3 -B scripts/testing/verify.py INTEGRATION identity
python3 -B scripts/testing/verify.py CHANGE COMMAND,EVENT
python3 -B scripts/testing/verify.py CERTIFICATION --dry-run
python3 -B scripts/testing/verify.py CERTIFICATION
python3 -B scripts/testing/verify.py QUARANTINE i5_r05
python3 -B scripts/testing/verify.py QUARANTINE i5_r12
```

`--dry-run` prints the exact Maven command, recipe methods, unselected JUnit
sources/sibling methods and filtering status; canonical expected identities are
resolved at execution from Maven's effective reactor and source inventory, not
from prior XML. Dry-run's pending canonical inventory is not a coverage proof;
it executes no Maven test. `--output /absolute/new/directory` selects the evidence
destination, which must not already exist. Default: unique temporary directory.
Keep/publish required raw evidence before removing temporary outputs.

The explicit `identity` and `foundation` recipes share cumulative expansion in
the selector; only their class lists/anchors differ in the registry. Identity's
local unit-feedback anchors are
`engine-consume-registration,domain-pot`; they avoid compiling the runtime/SQL
closure for pure feedback. This is a bounded contribution inside COMMAND, never
its canonical slice proof. CHANGE Identity uses the canonical COMMAND anchor.
The `foundation` recipe uses `infra-tx-spring`: FAST selects four H2 tests;
CHANGE and INTEGRATION select all 28 current foundation invocations. Its filtered
PASS remains operational feedback. `CHANGE FOUNDATION` is the unfiltered canonical
command and derives its own expected inventory from the effective model.
CHANGE/INTEGRATION also accept the canonical uppercase slice identifiers,
comma-separated. Those commands are the
**unfiltered** canonical `-pl <anchors> -am test`; they include heavy tests and the
quarantined methods if Maven pulls their module. INTEGRATION does not silently add
architecture-tests: declare that requirement and execute its canonical command
when §17 applies. CERTIFICATION is exactly `./mvnw --batch-mode
--no-transfer-progress test`, without a module/test filter or skip property. It
is the Maven portion of certification, not an automatic SPEC-10 certificate.
Required external protocols, environment variants, packaging and acceptance remain
due even when Maven succeeds.

### Coverage matrix for selection

| Component / risk | Reviewed decisive contributions | Selection and escalation |
|---|---|---|
| Domain / Pot | `PotTransitionsTest`, `PotAuthorizationTest`: versions, deletion and authorization; `InvitationAttachmentTest`, `ShareholderIdentityCollisionTest`: attachment/collision outcomes. | Identity FAST includes the adjacent invariants; changed Pot execution requires COMMAND and its actual SQL tests, not only this recipe. |
| Identity / Invitation | `IdentityValueContractsTest`, `IdentityTransitionsTest`, `ExecuteIdentityRequestTest`, `IdentityRequestContractTest`: immutable identity, stale token rejection, Link/Unlink, durable request contract. | FAST; CHANGE adds authority/history/schema and runtime tests. Future invitation/admission/consent changes need their own proofs; external ID-02 is not certified here. |
| Security / Authorization | `IdentityPrivilegesPostgresTest`: effective runtime permissions; `V3IdentityRequestCompositionPostgresTest`: preflight refusal, indirect exposure, logging and auxiliary privilege matrix. | CHANGE Identity. Shared security or HTTP/AuthN changes add WEB and every affected consumer, plus qualified external protocols. |
| Transaction / Atomicity | `IdentityConcurrencyPostgresTest`, `IdentityRequestExecutionPostgresTest`: races, rollback and durable effects; `AttemptTransactionsPostgresTest`, foundation transaction tests: real physical transaction boundaries. | CHANGE for Identity-local behavior; INTEGRATION for shared transaction changes, with every affected consumer and applicable adverse interleavings. |
| Events / Publication | `IdentityPublicationPostgresTest`: FK prefix/commit publication; `IdentitySchemaPostgresTest`: deferred constraints and non-Identity suffix access; global `EventPublicationContractArchitectureTest` when its gate is required: publication structure. | CHANGE Identity; INTEGRATION adds transaction/composition checks. Shared publication changes add COMMAND/EVENT/PROJECTION as actually affected. |
| Consumption / Discovery | `IdentityDiscoveryPostgresTest`, `V3IdentityRequestCompositionPostgresTest`: Identity source/checkpoints and real runtime; full `DiscoveryPostgresTest` proves broader windows and recovery. | Identity CHANGE does not run the entire Discovery stress suite. Discovery algorithm/routing/claim changes require its real affected campaigns and consumers. |
| Projection / READ | Existing READ JDBC/projection and task-runtime tests prove current binding/results/projection contracts; no complete L4 business projection is inferred. | PROJECTION plus actual changed READ owners; Identity recipe alone is insufficient. |
| Runtime / Assembly | `V3IdentityRequestCompositionPostgresTest`, `V3IdentityRequestMonolithPostgresTest`, `V3WorkerCompositionPostgresTest`, `V3PotCommandCompositionPostgresTest`: actual roots and neighboring participants. | CHANGE Worker root; INTEGRATION adds monolith/adjacent composition. Distribution changes add package/start/restart proofs. |
| Architecture | IdentityRequestContractTest checks sealed operations, interfaces and public record components locally; no global module added to Identity feedback. | Dependency/module/forbidden-boundary/isolation changes require §17's unfiltered global gate. |
| Concurrency / Recovery | Identity concurrency/publication tests retain independent SQL observations; broad L2 lifecycle/Discovery/Worker suites retain their own interleavings. | Identity races in CHANGE. Leases/claims/fencing/recovery changes require pertinent L2 adverse cases, including quarantined methods when their contract is affected. |
| Performance / Capacity | `V3WorkerPostgresTest`, `V3WorkerResourcesPostgresTest`, I5 temporal campaigns and SPEC-09/10 load protocols. | Applicable INTEGRATION and CERTIFICATION; excluded from Identity feedback by positive selection because these contracts are unchanged. Speed and reliability are separate dimensions. |

### Build commands and freshness

Native commands from `app/`, using the canonical affected consumer anchor(s):

```sh
./mvnw -pl runtime-command-consumption-worker -am compile
./mvnw -pl runtime-command-consumption-worker -am test-compile
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker -am package -DskipTests
./mvnw -pl runtime-command-consumption-worker -am install -DskipTests
```

For pure Identity/domain feedback, the smaller native command is
`./mvnw -pl engine-consume-registration,domain-pot -am compile`; it does not prove
the runtime consumers. Use `compile` for production compilation, `test-compile` when checking test code,
`test` for proof, `package` only when validating a deliverable, and `install` only
when another independent build really needs local artifacts. A composed reactor
resolves participating upstream projects without a preceding install. No automatic
`-amd`: explicitly add all affected consumers; `-am` only includes upstream closure.
`-DskipTests` skips execution but still permits test compilation; no test PASS is
produced. `-Dmaven.test.skip=true` also skips test compilation and is acceptable
only for an explicitly production-only artifact experiment, never a test proof.

Incremental compilation saves unchanged work but is not a source/bytecode
certification. Following deletions, package moves, branch switches or suspicious
stale output, clean the affected closure and rerun the required proof; use a fresh
checkout when qualification requires it. Record cold/clean, warm unchanged and
after-modification measurements separately. A warm no-op is not an incremental
change measurement. Do not depend on artifacts installed by a different SHA.

`-T 2` is an optional compilation experiment only after checking plugin safety and
measuring the same scope. It is not enabled by the supplied commands. PostgreSQL
campaigns remain serial at reactor level; fixtures, target directories and evidence
must not be shared by simultaneous Maven invocations. No container/context reuse
is introduced across isolated test fixtures, and no hidden local cache certifies
unchanged hypotheses. Maven/JDK, semantic settings, input hashes and PG image/
server observations belong to the evidence.

### Nominative quarantine, independent of cost

The sole register is `quarantine` in `verification-campaigns.json`: the exact
`V3WorkerResourcesPostgresTest#i5_r05` and `#i5_r12` methods. The present explicit
mandate authorizes operational isolation of these two scenarios only. It does
not exempt them from any mandatory contract/gate. No POM exclusion, annotation,
or assertion changes are made; all sibling methods remain available. Identity
feedback positively selects its relevant tests; it does not blacklist the Worker
class. Canonical slice/full commands remain completely unfiltered.

Each QUARANTINE invocation executes exactly one registered method with its real
oracle, captures raw diagnostics and reports its own PASS/FAIL/SKIP/INCONCLUSIVE.
A failure remains blocking whenever that scenario is normatively required.
`i5_r05` has both historical failure and later success observations. `i5_r12`'s
observed `runtime condition deadline` in runtime close is an unresolved physical
availability diagnostic, not a proven test-only defect. No timeout is increased.
Do not retry unchanged failures to manufacture success.

New quarantine entries require an explicit documented decision, exact methods,
failure evidence, affected invariant and retained canonical inclusion. Reintegration
requires diagnosis/correction, reproduction of the original failing condition and
applicable fresh integration/gates; remove the entry by a reviewed decision. A
single independent success does not close historical availability reserves.

### Evidence, selection controls and reuse

The selector records the exact command/expected invocation identities, input hashes,
tested HEAD and dirty state, toolchain and settings hash. Before any Java/Maven
subprocess it rejects nonempty `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS`, and unreviewed `.mvn/maven.config`,
`jvm.config` or `extensions.xml`. Recipe `-Dtest`/`failIfNoSpecifiedTests=false`
remain explicit argv, never implicit environment configuration.

Every run first executes `help:effective-pom` with the same selection and retains
the model and discovery log. Maven owns the reactor closure; current test sources
own the expected identities. Standard source roots, the effective Surefire
configuration and fixture ancestors are checked. Unreviewed configuration,
composed/inherited/dynamic/generated test discovery or providers cannot produce
PASS. The currently reviewed parameterized boolean provider is expanded to both
separate invocation names; other providers require an explicit collector review.
There is no promise that every canonical slice's current JUnit forms are supported.
Refusal/INCONCLUSIVE is preferable to an unproven canonical PASS.

The selector moves old XML to `previous-reports/` before execution and never counts
it. New XML, logs, effective model and freshly modified physical evidence are
archived with SHA-256. **Every** PASS, including canonical commands, requires a
nonempty expected inventory, exactly one fresh successful execution of every
expected identity, no unexpected/duplicate identity, consistent XML counters and
zero skips. Equal totals with different identities are insufficient. Missing,
stale or incomplete reports cannot pass. A nonzero Maven exit/failure/error is FAIL;
insufficient coverage or discovery is INCONCLUSIVE (preflight refusal exits 2).
The checkout lock prevents concurrent selector runs; native Maven commands must
follow the same single-owner discipline. Removing tests from sources changes the
obligation/input fingerprint and requires source review; old coverage is not reused.

External SQL fingerprints follow the selected test classes and referenced test
helpers, then the literal `scripts/v3/` loader inputs. The sorted paths and SHA-256
values are recorded and compared before/after test execution, alongside `app/`,
selector/registry and settings. A missing SQL file is refused. Helpers are traced
conservatively at file granularity (including their other SQL literals); this does
not hash every SQL file globally. FAST Identity and FOUNDATION have no external
SQL inputs; CHANGE Identity has six; current INTEGRATION has seven through the
Worker helper. App-local fixtures/migrations/resources and POM generation inputs
remain in the `app/` inventory. New loader idioms or indirect nonliteral filenames
require review; no inference of arbitrary external dependencies is claimed. Changed
SQL prevents proof equivalence/reuse; this tool introduces no result cache.

Compilation metrics distinguish files present under main/test/generated roots,
files in the configured source roots of selected Maven modules, and Compiler
announcements of recompilation. Singular/plural `source file(s)` and main/test
compiler invocations are counted. Generated inventories may overlap source roots;
do not add them together. These announcements count compiler input files, not
class outputs or independently observed javac reads. Exact per-file reuse is
UNKNOWN/null; resource copies are not Java compilation. No-op module messages and
announced recompilations remain inspectable in raw logs. Durations `seconds` cover
the test Maven process; `discovery_seconds` separately covers model discovery.

The [resolution report](../v3/Verification_Rationalization_Resolution.md) records
the independent audit counterexample, regression controls, FOUNDATION and Identity
replays, corrected historical counts and measured build comparisons.

These receipts supplement rather than replace SPEC-10's physical traces and lot
collectors. Test results alone do not prove every required variant. Save official
raw evidence under the retention policy; temporary logs are not durable proof.
Reuse follows §34: accessible verified artifacts, tested SHA, exact old→new
hypotheses/config/SQL/test/environment comparison and explicit attribution. An old
SHA never certifies a new SHA automatically. Changed assumptions or a required
fresh gate prohibit substitution.

Concise report convention:

```
Change / modified modules: ...
Risks: ...
Declared slices / primary / secondary: ...
Required operational level and mandatory unfiltered gates: ...
Executed commands / tested SHA / input hashes / counts / evidence: ...
Deferred campaigns and contract-based reason: ...
Result: PASS / FAIL / SKIP / NOT RUN / INCONCLUSIVE
Unexpected slice crossing: NONE / ...
Global architecture gate: NOT REQUIRED / RUN / NOT RUN (reason)
Full reactor: NOT REQUIRED / RUN / NOT RUN (reason)
Database: NONE / CURRENT / BASELINE / HISTORICAL (including transitive history)
Certification: NOT RUN / FAIL / INCONCLUSIVE / qualified PASS
```

Measured durations, costs, limitations and actual delivery commands are in the
[optimization report](Verification_Optimization_Report.md); the method-level
[inventory](evidence/verification-optimization/inventory.json) records observed
historical costs and source dependencies without turning missing observations
into PASS. Budgets above are targets, never measured claims.
