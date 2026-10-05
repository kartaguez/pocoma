# WP5 — Infra & Runtimes — execution record

## Baseline and pre-audit (before any `app/` edit)

Branch `v2-make-it-pull`; `git fetch origin` succeeded. HEAD and fetched origin were both `c592464922f0a2fb0550e19575ea816c1fcadd70`, divergence `0/0`, clean worktree. The requested `Modularity_Current_Target_Traceability.md` does not exist at the documented path; the TARGET topology, migration plan, CURRENT audit and production sources were used for this audit. The production Maven graph has 73 children and 314 direct internal production arcs when `test` and `provided` scope are excluded (the earlier 354 count used a different counting method). The current graph has no newly introduced edges because no implementation has begun.

| CURRENT | Remaining responsibility | TARGET owner | Direct Maven consumers | Bridge | WP5 action |
| --- | --- | --- | --- | --- | --- |
| `engine-command`, `engine-pot-command` | Empty production shells | `engine-consume-command`, `engine-write-pot` already hold behavior | none | B17/B19 shells | DELETE after test/reference audit |
| `engine-projection-task` | Empty production shell | `engine-produce-projection-task`, `engine-consume-projection-task`, `supra-consume-projection-task`, `port-projection` | balance/pot projection shells, PRIMARY, Event/Task runtimes, Event locator | B10 | DELETE after POM and fixture audit |
| `engine-registration` | Deprecated execution facade and duplicate Request/Outcome/User fact interfaces | `engine-consume-registration`, `engine-admit-registration`, PRIMARY adapters | PRIMARY, Registration Result runtime | B20 | REHOME adapters/tests to TARGET ports, then DELETE |
| `engine-projection-pot` | AUTH/READ_POT input loader interfaces | `engine-consume-projection-task` ports and PRIMARY loaders | PRIMARY, Task runtime | B8/B12 | REHOME then DELETE |
| `engine-projection-balance` | Historical balance source, calculator service and Task loader | `port-projection`, `engine-consume-projection-task`, `projector-pot`, PRIMARY adapter | PRIMARY, Task runtime | B8/B12 | REHOME then DELETE |
| `engine-core` | RecordedEvent/trace, legacy event SQL envelope, Pot partition hash | `domain-pot` and PRIMARY adapter | Registration shell, Event/LKV shell, Pot shell, tx adapter, PRIMARY, old admission shell | B5 | REHOME with consumers, then DELETE; no new core |
| `engine-processing-event` | Event port/candidate plus LKV advancement and model | firm Event: `engine-produce-projection-task`/`domain-event`; LKV owner undecided | PRIMARY, READ legacy, Event/LKV locators and Event runtime | B15 | REHOME firm Event only; KEEP_LKV_PROVISIONAL |
| `infra-persistence-jpa` | PRIMARY WRITE, discovery, results, historical loaders, locking | `infra-persistence-primary-jpa` | all nine runtimes | B12/B26 | RENAME; preserve repository/SQL/migration paths |
| `infra-persistence-projection-jdbc` | Exact projection store | same | Web/Task | none | KEEP_TARGET |
| `infra-persistence-read-jdbc` | CURRENT_BINDING adapter | same | Binding/Web | none | KEEP_TARGET |
| `infra-read-persistence` | Shared READ bootstrap/migrations and LKV store | firm Current Binding uses `infra-persistence-read-jdbc`; LKV physical owner undecided | Web, Task, Binding, LKV | B15 | KEEP_LKV_PROVISIONAL until physical split can be proved without migration change |
| `infra-tx-spring`, `infra-projection-validation-networknt` | Spring transaction provider, schema validation | same | runtimes | none | KEEP_TARGET |
| `locator-consumption-command`, `locator-consumption-event`, `locator-consumption-binding` | Empty production shells; their behavior already sits in capability supras/engines, PRIMARY, polling | respective TARGET owners | Event/Binding runtime for two; no Command consumer | B9/B14/B18 | DELETE after POM/test/reference audit |
| `locator-consumption-latest-known-version` | LKV reload/issue/failure behavior | undecided LKV engine/supra | LKV runtime | B15 | KEEP_LKV_PROVISIONAL |
| Eight firm runtimes | Spring bean selection, worker lifecycle, polling setup; Task catalog selection and Result/Registration compositions need structural review | same runtime roots; behavior in engines/supras/orchestrator | no runtime consumers | B21/B23/B26 | KEEP_TARGET; remove semantic remnants where proved |
| LKV runtime | LKV composition while topology open | undecided | none | B15 | KEEP_LKV_PROVISIONAL |

Audit inputs include child POM dependencies, Java production/test imports, Spring configurations and scan, runtime bean construction, test fixtures, and primary/READ resource paths. In particular the READ module owns V1–V14 migration resources and LKV storage, so deletion based on a Java import search would be invalid.

## Declared verification scope

Expected production impact: Maven graph, PRIMARY identity, firm legacy shells/locators, Registration adapters and projection loader ownership, Event/core values, and composition roots. Primary slice: PROJECTION. Secondary slices: WEB, COMMAND, EVENT, BINDING, Registration, Command Result, Registration Result; LKV if common infrastructure moves affect it. Canonical slice proof: `./mvnw -pl runtime-task-consumption-worker,runtime-web-api,runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-binding-consumption-worker,runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-latest-known-version-consumption-worker -am test`. Targeted Postgres proofs must cover Command, Registration no-orphan, Binding facts/C2, CURRENT_BINDING, Direct Results and exact projection persistence. Global architecture gate: `./mvnw -pl architecture-tests -am test` REQUIRED. Full reactor: `./mvnw test` REQUIRED. No historical migration proof is planned without migration change. Forbidden production impact: SQL/schema/migrations, LKV topology, changed business behavior, TARGET→legacy edges, runtime policy, cycles. STOP on an owner gap, necessary SQL change, new TARGET→legacy/cycle, unexpected slice coupling, or a business proof regression.

## Delivery status

WP5 implementation and verification are complete. WP6 remains not started. TBD-COMMAND-CONTRACT and TBD-E2U remain resolved; TBD-LKV remains open.

## Physical moves and behavioral boundary

The PRIMARY module `infra-persistence-jpa` was renamed to `infra-persistence-primary-jpa`, including its Java package. Its 29 Flyway resources have identical bytes at the new module path; the `db/migration` classpath path is unchanged. All nine runtimes compile against the new artifact. Projection exact persistence remains in `infra-persistence-projection-jdbc`; CURRENT_BINDING remains in `infra-persistence-read-jdbc`. The mixed `infra-read-persistence` module remains for the READ bootstrap/migrations and LKV store, without reclassifying LKV.

| Old responsibility/class | TARGET owner and action |
| --- | --- |
| `engine-core` `RecordedEvent`, `EventTraceMetadata` | `domain-pot` event package |
| `engine-core` `BusinessEventEnvelope` | PRIMARY outbox adapter package |
| `engine-core` `PotPartitioner` | PRIMARY outbox entity package |
| `engine-registration` execution facade | removed; callers use `engine-consume-registration.ExecuteRegistrationService` |
| `engine-registration` duplicate Request/Outcome store interfaces | removed; PRIMARY adapters implement `engine-admit-registration` / `engine-consume-registration` ports directly |
| `engine-registration` duplicate `UserCreatedFactPort` | removed; PRIMARY adapter implements `engine-consume-registration.UserCreatedFactPort` |
| `engine-projection-balance` historical source, calculator service, input loader | `engine-consume-projection-task.input`; historical SQL adapter remains PRIMARY |
| `engine-projection-pot` AUTH/READ_POT loader ports | `engine-consume-projection-task.input`; concrete historical loaders remain PRIMARY |
| `engine-projection-task` test-only shell | tests moved to `engine-consume-projection-task` |
| `locator-consumption-event` test-only shell | tests moved to `supra-consume-event` |
| `engine-command`, `engine-pot-command`, Command/Binding locator shells | empty POMs removed after Maven and source audit |

The Event→Task firm path already resides in `supra-consume-event` and `engine-produce-projection-task`. The remaining `engine-processing-event` classes are LKV model, advancement, event discovery/reload port, candidate, ordering key and the missing-event exception used by the LKV locator. No `engine-advance-pot-watermark` or `supra-consume-lkv` POM was created.

## Runtime-by-runtime composition audit

| Runtime | Firm composed path and WP5 action |
| --- | --- |
| `runtime-web-api` | Single Web root wires `supra-http-write` and `supra-http-read`, admit/read engines, Security provider and concrete adapters; PRIMARY artifact/package references renamed. GET Pot retains CURRENT_BINDING → U → AUTH@V → READ_POT@V. |
| `runtime-command-consumption-worker` | Polling from `orchestrator-poll-consumption`, `supra-consume-command`, `engine-consume-command`, `engine-write-pot`, PRIMARY; PRIMARY references renamed. |
| `runtime-event-consumption-worker` | Polling, `supra-consume-event`, `engine-produce-projection-task`, PRIMARY; empty locator POM removed. |
| `runtime-task-consumption-worker` | Polling, `supra-consume-projection-task`, `engine-consume-projection-task`, `projector-pot`, PRIMARY/projection/READ adapters; Task catalog remains selection of producers and configured projection types. |
| `runtime-binding-consumption-worker` | Polling, `supra-consume-binding`, `engine-materialize-current-binding`, READ and PRIMARY adapters; empty locator POM removed. |
| `runtime-registration-consumption-worker` | Polling, `supra-consume-registration`, `engine-consume-registration`, PRIMARY. |
| `runtime-command-result-consumption-worker` | Polling, `supra-consume-command-result`, `engine-materialize-command-result`, PRIMARY; no Result semantics moved into runtime. |
| `runtime-registration-result-consumption-worker` | Polling, `supra-consume-registration-result`, `engine-materialize-registration-result`, PRIMARY; legacy Registration dependency removed. |
| `runtime-latest-known-version-consumption-worker` | Existing LKV root kept provisionally; common PRIMARY rename compiled and included in the declared slice. |

All firm runtimes are composition roots in the resulting graph, with no `runtime-* → runtime-*` Maven arc. Generic polling remains in `orchestrator-poll-consumption`; that module has no capability-specific dependency. The WP5 structural guard checks graph cycles, removed firm modules, domain/projector framework imports, generic Consumption, runtime isolation, supra/infra direction, Pot READ and Direct Result forbidden arcs.

## Bridge ledger

| Bridge | WP5 state |
| --- | --- |
| B1 | already removed / never created |
| B2 | already removed / never created |
| B3 | already removed / never created |
| B4 | already removed / never created |
| B5 | already removed / never created; `engine-core` POM now removed |
| B6 | already removed in WP2 |
| B7 | already removed / never created |
| B8 | removed by WP5 with projection balance/pot shells; pure projector stays `projector-pot` |
| B9 | removed by WP5 with Event locator shell |
| B10 | removed by WP5 with Task shell |
| B11 | already removed in WP2 |
| B12 | removed by WP5 with projection loader rehome and PRIMARY rename |
| B13 | already removed in WP2 |
| B14 | removed by WP5 with Binding locator shell |
| B15 | LKV provisional: `engine-processing-event`, `infra-read-persistence`, LKV locator/runtime |
| B16 | already removed in WP3 |
| B17 | removed by WP5: empty Command shell |
| B18 | removed by WP5: empty Command locator shell |
| B19 | removed by WP5: empty `orchestrator-command-admission` shell |
| B20 | removed by WP5: Registration facade and duplicate ports |
| B21 | already removed in WP4; remaining runtime composition is TARGET |
| B22 | already removed in WP4 |
| B23 | already removed in WP4; remaining runtime composition is TARGET |
| B24 | already removed in WP4 |
| B25 | already removed in POST-WP4.B: PRIMARY E→U resolver deleted, GET Pot uses CURRENT_BINDING |
| B26 | old PRIMARY identity removed by WP5; configuration still uses Spring composition in target runtime/infra packages |

The empty `binding-pot-command-spring` shell was also removed after its WP3 dispatch move. The WP6 D.24 domain shells (`domain-pot-projection`, `domain-projection-balance`) remain outside the firm D.19/D.21/D.22/D.23 removals. They should not be counted as LKV debt. They require their own D.24/D.27 accounting and are not silently promoted to TARGET.

## Maven graph and remaining modules

Using the same XML method before and after WP5 (child modules, direct internal production dependencies, excluding `test` and `provided`), the graph moved from **73 POM / 314 arcs** to **61 POM / 244 arcs**. One POM was renamed (`infra-persistence-jpa` → `infra-persistence-primary-jpa`); twelve additional POMs were removed. A DFS over all 61 modules found **zero cycles**. After normalizing the PRIMARY rename, the only added internal arc is `engine-consume-projection-task → domain-pot`, needed for the relocated historical input model. There is **no new TARGET→legacy arc**.

The nine surviving TARGET→legacy arcs predate WP5 and are documented exceptions pending either LKV or D.24/WP6: `engine-read-pot → domain-pot-projection`, `infra-persistence-primary-jpa → domain-projection-balance`, `infra-persistence-primary-jpa → engine-processing-event`, `runtime-binding-consumption-worker → infra-read-persistence`, `runtime-event-consumption-worker → domain-pot-projection`, `runtime-event-consumption-worker → engine-processing-event`, `runtime-task-consumption-worker → domain-pot-projection`, `runtime-task-consumption-worker → infra-read-persistence`, and `runtime-web-api → infra-read-persistence`. The LKV residue is kept, not redesigned; the two domain shells are reserved for D.24.

No runtime depends on another runtime. No firm module depends on the deleted POMs. The old named modules still present are `engine-processing-event` (11 LKV-related Java types), `infra-read-persistence` (LKV adapter and shared READ bootstrap/migration classes and resources), `locator-consumption-latest-known-version` (locator and failure policy), `runtime-latest-known-version-consumption-worker` (LKV composition), plus the empty `domain-pot-projection` and `domain-projection-balance` POMs planned for WP6 D.24.

## Verification record

The required multi-slice command is the nine-anchor command declared above. It **PASSED** with Docker access after an initial sandboxed attempt failed to reach the Docker socket; the failure was environmental and occurred before any Postgres assertions. The passing run included 348 PRIMARY test cases, 37 shared READ tests, exact projection persistence tests, and the Web/Command/Event/Task/Binding/Registration/Result/LKV runtime suites. It covers Command fencing, Registration no-orphan, Binding authority/facts and C2, CURRENT_BINDING, immutable Direct Results and exact projection persistence/wiring.

`./mvnw -pl architecture-tests -am test` **PASSED** after replacing four guards tied to deleted physical paths with guards for the TARGET owners. The new `Wp5TargetTopologyTest` checks removed POMs, LKV retention, graph cycles and forbidden POM arcs, plus pure domain/projector source imports. The previous `HexagonalArchitectureTest` purity guard was narrowed to the actual pure projector package while the Task engine's input loaders were given access to projector input types. No guard was disabled.

All 44 SQL files in the reactor have identical bytes to the baseline after normalizing the PRIMARY module path. There are no SQL, schema or migration content changes. No historical migration proof was added; Postgres tests exercise current migration/bootstrap paths.

## Closure and delivery

Commands actually executed from `app/`:

- Nine-anchor `-am -DskipTests compile` after PRIMARY rename — PASS.
- Selected Projection/Event `-am -DskipTests test` after removal of empty shells — PASS.
- Nine-anchor `-am -DskipTests test` after D.19/D.22 moves — PASS after correcting one test fixture type substitution.
- `./mvnw -pl architecture-tests -am -DskipTests test` — PASS after removing stale architecture-test POM dependencies.
- Focused architecture guards with `-Dtest=HexagonalArchitectureTest,Pcl4LegacyQueryReadAbsenceTest,Pcl6MonolithAbsenceTest,RegistrationModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS after replacing physical legacy path expectations.
- `./mvnw -pl architecture-tests -am -Dtest=Wp5TargetTopologyTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS.
- Declared nine-anchor `-am test` — first sandboxed run could not reach Docker; first escalated run exposed a missing-event exception still required by LKV and was corrected without changing LKV topology; final escalated run **PASS**.
- `./mvnw -pl architecture-tests -am test` — first run identified four obsolete physical guard expectations, corrected; final escalated run **PASS**.
- `./mvnw test` — **PASS**, required as broad Maven restructuring gate.
- `git diff --check` — PASS after removing trailing whitespace from edited POMs.

Unexpected production slice crossings: **none**. LKV compilation and tests were declared because PRIMARY moved; no LKV semantics changed. Global gate required: **yes**, because POMs/guards changed. Full reactor required and run: **yes**, because WP5 materially restructured the graph. Postgres proof: current schema via Testcontainers across PRIMARY, READ, projection persistence, Web and workers; no historical upgrade gate was required. No business proof regression remains.

Delivery branch: `v2-make-it-pull`. The implementation and report are committed and pushed together; the exact commit ID, fetched origin divergence and clean working-tree proof are recorded in the delivery response because a commit cannot contain its own hash. The baseline was `c592464922f0a2fb0550e19575ea816c1fcadd70`; the resulting HEAD is its WP5 delivery descendant.

WP5: DONE

TBD-COMMAND-CONTRACT: RESOLVED
TBD-E2U: RESOLVED
TBD-LKV: OPEN

WP6: NOT STARTED
