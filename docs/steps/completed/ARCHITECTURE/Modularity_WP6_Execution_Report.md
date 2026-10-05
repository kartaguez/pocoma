# WP6 — Final consolidation — execution report

## 1. Baseline

Branch `v2-make-it-pull`. `git fetch origin` succeeded. Before modification, local HEAD and `origin/v2-make-it-pull` were both `e9c55573bcc701b0aced0f6a575ad4b59675ddc4`, divergence `0/0`, with a clean working tree.

## 2. Pre-audit CURRENT

The reactor contained 61 child POMs and 244 direct internal production-scope arcs, with zero cycles and zero missing internal dependency. Nine runtimes were composition roots and no runtime depended on another runtime. The remaining legacy/provisional POMs were `engine-processing-event`, `locator-consumption-latest-known-version`, `infra-read-persistence`, `domain-pot-projection`, and `domain-projection-balance`. `engine-core` was already absent.

The remaining locator POM was LKV only. The mixed Event engine held LKV model/use case/write port plus Event discovery/reload contracts. The mixed READ infra held the READ Flyway bootstrap, CURRENT_BINDING wiring and LKV max-upsert. The two domain POMs were production-empty shells, with Balance tests still attached to one shell. The nine baseline TARGET→legacy arcs documented by WP5 were confirmed before implementation.

## 3. Residue matrix

| CURRENT residue | Responsibility | TARGET owner | WP6 action |
| --- | --- | --- | --- |
| `engine-processing-event` LKV types | monotone LKV materialization | `engine-materialize-latest-known-version` | CREATE/REHOME/DELETE |
| `engine-processing-event` Event contracts/order/error | LKV candidate and authoritative reload protocol | `supra-consume-lkv` | CREATE/REHOME/DELETE |
| LKV locator POM | candidate/reload/execution/failure adaptation | `supra-consume-lkv` | REHOME/DELETE |
| `infra-read-persistence` | READ bootstrap, CURRENT_BINDING and LKV JDBC | `infra-persistence-read-jdbc` | REHOME/DELETE |
| `domain-pot-projection` | empty compatibility shell | existing exact projection owners | DELETE |
| `domain-projection-balance` | empty production shell; domain tests | `domain-pot` | REHOME tests/DELETE |
| Event/ProjectionTask tests under mixed engine | projection task production policy | `engine-produce-projection-task` | REHOME |
| historical docs presented as future TARGET | migration provenance | final topology/traceability/report | DOC_ONLY |

No residue lacked a TARGET owner.

## 4. Verification decision

Primary slice: LKV, canonical command `./mvnw -pl runtime-latest-known-version-consumption-worker -am test`. Secondary slices: WEB, EVENT, PROJECTION and BINDING because READ bootstrap ownership and shell dependencies moved. The Final Architectural Journey Proofs cover Command, Registration, Command Result and Registration Result assembly. Forbidden production impact: SQL/schema/migration content, business semantics, TARGET→legacy dependency, runtime→runtime edge, cycle, or new architectural TBD. Global architecture gate and full reactor are required because WP6 changes POM boundaries and closes the migration. Database verification uses current Postgres/Testcontainers paths; historical migration compatibility is not a separate gate because migration contents and locations are unchanged. STOP conditions were not reached.

## 5. LKV before/after

Before WP6, LKV spanned the mixed Event engine, a legacy locator, mixed READ infra and its independent runtime. After WP6:

```text
runtime-latest-known-version-consumption-worker
  -> supra-consume-lkv
       -> engine-materialize-latest-known-version
  -> generic Consumption
  -> PRIMARY Event discovery/reload adapters
  -> infra-persistence-read-jdbc max-upsert
  -> transaction adapter
```

The materializer accepts durable `Event(PotId,V)` data and advances `PotId → max successfully consumed PotVersion`. It never requires `R+1`, permits gaps, ignores equal/stale versions and never reads PRIMARY Pot state. The Postgres proof now explicitly executes `V10,V12,V11` and obtains 12. No LKV read engine was created.

## 6. CURRENT_BINDING final

`engine-materialize-current-binding`, `engine-read-current-binding`, `supra-consume-binding` and `runtime-binding-consumption-worker` remain specialized. CURRENT_BINDING retains ATTACHED/DETACHED payloads and same-revision divergence detection. It shares the C2 conceptual family with LKV, not a generic physical engine.

## 7. Exact Projection versus convergent index

AUTH@V, READ_POT@V and POT_BALANCES@V remain immutable exact historical artifacts produced through ProjectionTask. CURRENT_BINDING and LKV are mutable C2 convergent current-state indexes and do not use ProjectionTask. The shared operational base remains generic Consumption.

## 8–10. Module changes

Created: `engine-materialize-latest-known-version`, `supra-consume-lkv`. Renamed: none. Deleted: `engine-processing-event`, `locator-consumption-latest-known-version`, `infra-read-persistence`, `domain-pot-projection`, `domain-projection-balance`.

## 11. Packages/classes rehomed

LKV input/model/update/use case/write port moved to `com.kartaguez.pocoma.engine.materialize.latestknownversion`. LKV candidate, ordering, reload/discovery ports, missing-input error, locator and failure policy moved to `com.kartaguez.pocoma.supra.consume.lkv`. READ configuration, migrator, properties, qualifier and both JDBC adapters now live under `com.kartaguez.pocoma.infra.persistence.read.jdbc`. Balance domain tests moved to `domain-pot`; Event→ProjectionTask policy/model tests moved to `engine-produce-projection-task`.

## 12–13. Bridges and locators

B15, the final LKV provisional bridge, is removed. B1–B14 and B16–B26 were already removed or never created according to the WP5 ledger. No migration bridge remains. No locator Maven POM remains; locator classes are capability-owned inside their final supras.

## 14–15. Runtimes and infra

All nine runtimes remain independent composition roots. The LKV runtime is preserved as an independent Event consumer. PRIMARY continues to own Event discovery/reload and generic Consumption persistence. `infra-persistence-read-jdbc` owns the specialized CURRENT_BINDING and LKV stores plus the unchanged READ bootstrap. Exact projection persistence remains separate.

## 16. Architecture guards

The final topology guard rejects removed POMs, cycles, missing owners, engine→runtime, runtime→runtime, supra→concrete infra, generic Consumption→business capability, and LKV/ProjectionTask coupling. ArchUnit protects domain/projector purity, LKV supra purity, READ/PRIMARY separation and runtime isolation. `CURRENT_BINDING !→ ProjectionTask`, `LKV !→ ProjectionTask`, `ProjectionTask !→ LKV`, and `LKV engine !→ PRIMARY` are explicit module guards.

## 17–21. Final Architectural Journey Proofs

All journeys use the existing `architecture-tests` system boundary with real Spring contexts, HTTP where applicable, real worker polling entry points, PostgreSQL/Testcontainers and isolated data.

| Journey | Proof | Result |
| --- | --- | --- |
| A Registration → Binding → CURRENT_BINDING | HTTP registration; Registration, Result and Binding workers; CURRENT_BINDING read; first Command | PASS |
| B Command → Event → Projection → GET Pot | HTTP Command admission; Command/Event/Task workers; exact AUTH@V and READ_POT@V; HTTP GET | PASS |
| C Command → Command Result | asynchronous Command/Result workers; owner visibility, opaque other identity, detach/rebind independence | PASS |
| D Registration → Registration Result | asynchronous Registration/Result workers; owner visibility, opaque other identity, detach independence | PASS |
| E Event → independent LKV + Projection | same durable Event; LKV reaches V while zero ProjectionTasks exist; Event then Task pipelines converge independently | PASS |

Journeys A+B compose the first-user path: a new external identity registers, receives a User/Binding, converges CURRENT_BINDING, submits a Pot command, completes asynchronous WRITE→READ processing, and reads the exact state.

## 22. PostgreSQL proofs

The LKV slice passes its unit and Postgres suites, including max-upsert, out-of-order/gap behavior, rollback, retry, fencing and independent consumer identity. The journey suite exercises PRIMARY, Registration, Binding/CURRENT_BINDING, Command, Direct Results and exact Projection persistence through real assembled contexts. The full reactor also reruns the retained specialized PRIMARY, Binding/CURRENT_BINDING, Command, Registration, Direct Result and Projection tests in their owner modules. No specialized proof was replaced by an E2E happy path.

## 23. Final Maven graph

58 child POMs: 57 production POMs and one verification POM. 234 direct internal production-scope arcs using the same XML counting method as WP5. Cycles: 0. Missing internal dependencies: 0. TARGET→legacy arcs: 0. Legacy migration POMs: 0. Provisional/TBD POMs: 0.

## 24. TARGET comparison

The final topology implements the responsibilities and boundaries of the TARGET plus the resolved post-WP4 Command contract and LKV decisions. Historical 54-POM/147-arc counts are superseded design snapshots. The delivered graph is compared by ownership and dependency direction, not forced to those counts.

## 25. Technical debt

DEBT-MOD-01 is RESOLVED by the verified final topology. DEBT-WA6-01 and DEBT-WA6-02 remain OPEN/LOW because they concern the semantic completeness of lock/SQL guards and were not changed by WP6. Architectural TBDs are decisions, not technical debt; all three are resolved.

## 26. SQL/schema/migration diff

All 47 source SQL files remain present. The 14 READ migration resources have byte-identical content to baseline after their module-path move, and retain `classpath:db/read-store/migration`. No SQL, schema or migration content change exists.

## 27–28. Global gates

Architecture gate: `./mvnw -pl architecture-tests -am test` PASS, with 119 tests in `architecture-tests`, zero failure, zero error and zero skipped; Maven total time 3 min 13 s. Full reactor: `./mvnw test` PASS for all 58 child modules; aggregated Surefire XML reports contain 1,087 tests, zero failure, zero error and zero skipped; Maven total time 3 min 09 s. Historical migration gate: not required because migration content and lookup location did not change.

Commands actually executed include the canonical LKV slice, the five-journey system suite, the architecture gate and the full reactor. The first sandboxed LKV run could not reach Docker; its escalated rerun exposed a stale Spring auto-configuration registration, which was corrected before the final PASS. An initial architecture gate exposed guards still pointing at deleted provisional paths; the guards were moved to their final owners and the exact final command then passed. No unexpected production slice crossing occurred. The global gate and full reactor were both required by the declared transversal topology scope.

```text
./mvnw -pl runtime-latest-known-version-consumption-worker -am test
./mvnw -pl architecture-tests -am -Dtest=CommandCompletionE2EPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test -q
./mvnw -pl architecture-tests -am test
./mvnw test
```

## 29–30. TBD and remaining architectural work

`TBD-COMMAND-CONTRACT`, `TBD-E2U` and `TBD-LKV` are RESOLVED. No known architectural TBD or required modularity-migration work remains. The two unrelated LOW guard debts remain in the technical-debt register.

## 31–34. Delivery

The final commit identity, push, divergence and clean working-tree evidence are reported with the delivery because a commit cannot contain its own hash.

```text
WP6: DONE

FINAL ARCHITECTURE:
  cycles: 0
  TARGET→legacy: 0
  legacy migration POMs: 0
  architectural TBDs: 0

FINAL JOURNEYS:
  Registration → Binding → CURRENT_BINDING: PASS
  Command → Event → Projection → GET Pot: PASS
  Command → Command Result: PASS
  Registration → Registration Result: PASS
  Event → independent LKV + Projection pipelines: PASS

TBD-COMMAND-CONTRACT: RESOLVED
TBD-E2U: RESOLVED
TBD-LKV: RESOLVED

SQL/SCHEMA/MIGRATIONS: UNCHANGED
ARCHITECTURE GATE: PASS — 119 tests, 0 failure, 0 error, 0 skipped — 3 min 13 s
FULL REACTOR: PASS — 58 child modules; 1,087 tests, 0 failure, 0 error, 0 skipped — 3 min 09 s
```
