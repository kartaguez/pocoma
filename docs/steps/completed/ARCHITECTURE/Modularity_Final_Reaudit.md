# Modularity — final independent re-audit

Date: 2026-10-05

Branch: `v2-make-it-pull`

Audited baseline: `0ebc01ea7cc8f585bdff844aefb9bdd9f7d7e98c`

## 1. Purpose and evidence chain

This re-audit is the bounded independent verification requested after the [final independent audit](Modularity_Final_Independent_Audit.md) returned **FAIL** and the [POST-WP6 closure lot](Modularity_Post_WP6_Closure_Report.md) claimed to resolve its five findings. It does not reopen the complete WP1–WP6 architecture audit and introduces no refactoring.

The evidence chain is:

> FAIL → B-01, B-02, M-01, M-02, M-03 → POST-WP6 corrections → independent re-verification → **PASS**

The final topology remains documented in [Modularity_Target_Topology.md](Modularity_Target_Topology.md), its physical traceability in [Modularity_Current_Target_Traceability.md](Modularity_Current_Target_Traceability.md), and the original convergence proof in [Modularity_WP6_Execution_Report.md](Modularity_WP6_Execution_Report.md).

## 2. Baseline and verification scope

Before any modification:

- `git fetch origin`: success;
- local `HEAD`: `0ebc01ea7cc8f585bdff844aefb9bdd9f7d7e98c`;
- `origin/v2-make-it-pull`: `0ebc01ea7cc8f585bdff844aefb9bdd9f7d7e98c`;
- divergence: `0/0`;
- working tree: clean.

The verification policy was read before application verification. The declared primary slice was **EVENT**, with **PROJECTION**, **LKV**, **COMMAND** and **BINDING** crossed only by the required final journeys. No implementation change was planned. The global architecture gate and the full clean reactor were required explicitly as closure proofs. No unexpected slice crossing occurred. Database verification was limited to the POST-WP6 diff and existing integration proofs because no schema or migration changed.

## 3. POST-WP6 diff

Range: `2fdfbf92baa35fbcad18f028c8de473e8f49069b..0ebc01ea7cc8f585bdff844aefb9bdd9f7d7e98c`.

The range contains one commit, `0ebc01ea refactor: close final modularity findings`, and 25 paths (`323` insertions, `316` deletions). Production changes are confined to removal of the Consumption compatibility surface, adaptation of its direct callers, removal of the stale Event→Command Maven dependency, and the associated comments. Test changes remove legacy API coverage, adapt callers, strengthen the topology guard, and correct the LKV test name. Documentation changes describe those same corrections and add the closure report.

No unrelated business behavior, abstraction, transverse dependency, relocation, bridge or compatibility layer was introduced.

## 4. Finding re-verification

### B-01 — embedded legacy Consumption compatibility: RESOLVED

Repository-wide active-code searches and targeted consumer inspection confirm:

- `ClaimToken` is absent from production and test code;
- `Claim.compatibilityKey` and deprecated Consumption transitions/overloads are absent;
- the old `ConsumptionKey` mapping constructors are absent;
- the old `ConsumptionSlot` shortcuts and legacy slot-id derivation are absent;
- the test-only discovery overload is absent;
- callers use `ClaimId`, `slotId`, explicit Consumption identities and explicit TARGET segmentation coordinates.

The removed names were also searched outside their former files. Remaining occurrences are historical audit evidence, not renamed, copied, moved or wrapped active compatibility. Embedded legacy compatibility clusters: **0**.

The strengthened `Wp5TargetTopologyTest` does not rely on one obsolete spelling alone: it asserts the exact authorized record components of `Claim`, `ConsumptionKey` and `ConsumptionSlot`, rejects deprecated declarations across `domain-consumption`, and independently asserts physical absence of `ClaimToken`. It therefore protects the compatibility surface that caused B-01.

### B-02 — Event runtime production dependency on Command engine: RESOLVED

Inspection of the pre-correction tree confirms that `engine-consume-command` was declared by `runtime-event-consumption-worker` but used only by `PocomaProjectionMaterializationPolicyTest`. There was no import or semantic call in `src/main`, no Spring bean, and no runtime wiring that required it. Classification **A — stale dependency only** is confirmed.

In the final tree the POM arc and the test-only import/assertion are gone. The topology guard explicitly forbids the Event runtime→Command engine arc. Event→ProjectionTask and Event→LKV remain independently exercised by the PostgreSQL journey proof. Unexplained production arcs: **0**.

### M-01 — stale documentation/comments: RESOLVED

The active Consumption transaction document, B16 comments and Consumption comments now describe the TARGET API and ownership. Searches for the superseded assertions in CURRENT documentation found none. Explicitly historical reports retain their original wording as evidence.

### M-02 — contaminated test accounting: RESOLVED

The final method was verified, not inferred from an accumulated count:

1. `./mvnw clean` completed successfully;
2. immediately afterward there were zero Surefire XML reports, zero orphan reports and zero orphan target/report directories;
3. `./mvnw test` then created 230 fresh Surefire XML reports;
4. those reports contain exactly **1061 tests, 0 failures, 0 errors, 0 skipped**.

The oldest and newest reports both belong to the post-clean run window. The final count therefore contains only the CURRENT run.

### M-03 — inaccurate LKV test naming: RESOLVED

The renamed test is `convergesToTwelveFromDurableInsertionOrderTenTwelveElevenWithoutCreatingProjectionWork`. Its durable insertion/observation inputs remain V10, V12, V11 and its final asserted LKV is 12. The name claims convergence from observable durable order and no Projection work; it does not claim an unobserved internal application order.

## 5. Independent final graph

The graph was recomputed from the reactor POM and every child POM, excluding the verification POM as a production source and distinguishing compile/runtime from all scopes:

| Measure | Result |
|---|---:|
| Reactor children | 58 |
| Production POMs | 57 |
| Verification POMs | 1 |
| Production internal direct arcs | 226 |
| All-scope internal direct arcs | 234 |
| Cycles | 0 |
| Missing internal dependencies | 0 |
| TARGET→legacy arcs | 0 |
| Legacy/provisional POMs | 0 |

The eight additional all-scope arcs are verification-only. The `architecture-tests` aggregator's own dependencies are not production-module outgoing arcs and were not incorrectly added to the production graph.

## 6. Embedded legacy, CURRENT/TARGET and invariants

The explicit scan inside TARGET POMs found no known legacy Consumption compatibility, hidden bridge or migration compatibility cluster. Physical modules match the final topology and traceability documents. Every responsibility remains owned, no provisional physical boundary exists, and every production arc is explained.

Targeted inspection plus the passing architecture guards confirm the structural invariants:

- domain does not depend on Spring, JPA, runtime or infrastructure;
- projector does not depend on SQL, Spring, runtime, infrastructure or engine;
- engine does not depend on runtime;
- supra does not depend on concrete infrastructure;
- runtime does not depend on runtime;
- generic Consumption does not depend on concrete business capabilities;
- CURRENT_BINDING and LKV do not depend on ProjectionTask;
- ProjectionTask does not depend on LKV;
- GET Pot does not read PRIMARY E→U;
- Direct Results do not depend on CURRENT_BINDING.

## 7. TBDs and closure items

Active-source and CURRENT-document searches covered `TBD`, `TODO`, `FIXME`, `provisional`, `temporary`, `compatibility`, `legacy`, `bridge` and `unresolved`. Occurrences were classified rather than counted blindly:

- historical/superseded reports preserve prior decisions and findings;
- “temporary” operational failures and claim ownership describe runtime duration, not provisional architecture;
- retained physical JSON/SQL compatibility comments describe stable persistence formats, not Modularity migration bridges;
- the product TODO for future Pot authorization is outside this module-boundary closure;
- TARGET segmentation types are current processing coordinates, not legacy bridges.

Architectural TBDs: **0**. Implicit Modularity closure items: **0**. Migration bridges: **0**.

## 8. SQL, schema and migrations

There are **47 tracked SQL files**. The POST-WP6 diff contains no SQL file, migration, schema definition or SQL content change. Historical migration replay was therefore neither necessary nor authorized by the verification policy; the required PostgreSQL journey tests exercised the current trusted baseline.

## 9. Executed verification

### Architecture gate

Command: `./mvnw -pl architecture-tests -am test`

Result: **PASS**, including `architecture-tests` **120/120**, 0 failures, 0 errors, 0 skipped. Docker discovery first rejected `/var/run/docker.sock`, then used the configured user socket and completed its PostgreSQL proofs successfully.

### Final architectural journeys

The canonical gate/current integration reports verify:

| Journey | Result |
|---|---|
| A — Registration → Binding → CURRENT_BINDING | PASS |
| B — Command → Event → Projection → GET Pot | PASS |
| C — Command → Command Result | PASS |
| D — Registration → Registration Result | PASS |
| E — Event → independent LKV / Projection | PASS |
| C2 — durable V10,V12,V11 → final LKV 12 | PASS |

### Clean full reactor

Commands: `./mvnw clean`, then `./mvnw test`.

Result: **PASS — 1061/1061 CURRENT tests**, 0 failures, 0 errors, 0 skipped. The full reactor was run because the final closure instructions explicitly require a global integration proof, not because the documentary closure crossed an application slice.

## 10. Verdict and closure authorization

All five prior findings are resolved. No correction introduced an architectural regression. Every stated closure criterion is true:

- embedded legacy = 0;
- unexplained production arcs = 0;
- legacy/provisional POMs = 0;
- migration bridges = 0;
- architectural TBDs = 0;
- implicit closure items = 0;
- cycles = 0;
- missing internal dependencies = 0;
- TARGET→legacy = 0;
- architecture gate, journeys and clean full reactor = PASS;
- SQL/schema/migrations = unchanged.

Verdict: **PASS**.

This PASS authorizes documentary closure of the Modularity work step and closure of `DEBT-MOD-01`. It does not alter `DEBT-WA6-01` or `DEBT-WA6-02`.
