Pocoma Reactor Verification Policy

Status: NORMATIVE

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