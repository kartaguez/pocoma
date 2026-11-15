# WP2 — Projection & Technical READ — execution report

## Baseline and scope

- Branch: `v2-make-it-pull`. Initial HEAD and `origin/v2-make-it-pull`: `e09a791b59e83d6640d50606054873da03efe876`; divergence `0/0`; clean working tree; no commit since WP1.
- Authority reviewed before Java/POM edits: CURRENT audit, TARGET topology, migration plan, WP1 execution report, and `Reactor_Verification_Policy.md`. The TARGET topology remains normative. D.06–D.11 only; WP3–WP6 were not started.
- Declared primary slice: PROJECTION. Secondary slices: EVENT, BINDING, WEB, LKV. Architecture gate required because Maven boundaries change. Full reactor required because the restructure crosses several slices and resolves the historical `engine-read-projection` collision. No schema, migration, or SQL changes were planned or made.

## Inventory before implementation

| Responsibility | CURRENT source at `e09a791b` |
| --- | --- |
| AUTH and READ_POT | `engine-projection-pot` projectors, inputs and loader contracts; definitions in `domain-pot-projection` |
| POT_BALANCES | `engine-projection-balance` projector and historical loader, `domain-projection-balance` pure calculator/values |
| Event → Task | `engine-processing-event` policy and discovery contracts, `locator-consumption-event` source/finalizer, policy table in runtime Event, PRIMARY discovery SQL and Task store |
| Task consumption | `engine-projection-task` preparation/finalization/catalog; B6 `ProjectionTaskConsumptionOrchestrator` in runtime Task |
| Exact @V | `engine-projection-read` reader, `infra-projection-persistence` root/artifact/failure store, `infra-projection-json-schema` validator |
| Current Binding | old `engine-read-projection` values/GET/ports, `locator-consumption-binding`, `infra-read-persistence` JDBC adapter |
| LKV | old `engine-read-projection`, `locator-consumption-latest-known-version`, `infra-read-persistence`, runtime LKV |
| Historical PRIMARY inputs | `HistoricalPotSnapshotSource` and reconstruction error in old `engine-read-projection`; Pot/Balance loaders and their adapters in `infra-persistence-jpa` |

## Firm boundaries delivered

| POM | CURRENT source → WP2 role | Direct internal dependencies after WP2 | Bridge |
| --- | --- | --- | --- |
| `projector-pot` | Pot and Balance projectors/inputs extracted from `engine-projection-pot`, `engine-projection-balance`; definitions and balance values rehomed to their domain POMs | `domain-pot`, `domain-projection` | Old projector POMs retain historical loader roles; no projector delegate required |
| `engine-produce-projection-task` | Event decision table and task ensure from `engine-processing-event`, runtime Event and locator | `domain-event`, `domain-pot`, `domain-projection`, `port-projection` | B9 old Event locator POM |
| `engine-consume-projection-task` | Preparation, publication/failure finalization and B6 orchestrator from `engine-projection-task` and runtime Task | `domain-projection`, `port-transaction`, `port-projection`, `engine-consumption`, `orchestrator-consumption`, `projector-pot` | B10 old Task POM; historical snapshot source contract here while PRIMARY owns its adapter |
| `supra-consume-event` | Metadata-only candidate source, fenced issue/finalization glue from old Event locator | `orchestrator-consumption`, `engine-produce-projection-task` | B9 |
| `supra-consume-projection-task` | Task candidate paging/reload from runtime Task | `orchestrator-consumption`, `engine-consume-projection-task` | None |
| `engine-read-projection` | `engine-projection-read` renamed after deleting historical homonym; exact `ProjectionKey` lookup and output revalidation | `domain-projection`, `port-projection` | B11 removed |
| `engine-materialize-current-binding` | Binding discovery contracts and direct fact-to-current service from historical read engine and locator | `domain-user-identity`, `port-transaction`, `engine-consumption` | B14 old Binding locator POM |
| `engine-read-current-binding` | GET service/use case extracted from historical read engine | `domain-user-identity` | B13 removed |
| `supra-consume-binding` | Fact candidate/reload/issue mapping from old Binding locator | `orchestrator-consumption`, `engine-materialize-current-binding` | B14 |
| `infra-persistence-projection-jpa` | Exact root/artifact/failure store renamed from `infra-projection-persistence` | `domain-projection`, `port-projection` | No old Maven identity |
| `infra-projection-validation-networknt` | JSON Schema validation renamed from `infra-projection-json-schema` | `domain-projection`, `port-projection` | No old Maven identity |
| `infra-persistence-read-jpa` | Only JDBC CURRENT_BINDING adapter extracted from `infra-read-persistence` | `engine-materialize-current-binding`, `engine-read-current-binding` | Historical READ auto-configuration/migrations remain in CURRENT module |

The Task runtime now composes generic polling, the Task supra, the Task engine, projectors and existing adapters. It no longer owns `ProjectionTaskConsumptionOrchestrator`. Event policy is in the producing engine. Binding materialization is in its own engine. SQL discovery and historical Pot/Balance loaders remain in PRIMARY; projection persistence and validation remain separate.

## Collision, LKV and identity debts

The old `engine-read-projection` POM held Current Binding, LKV and a historical Pot snapshot source. Binding values and ports moved to `domain-user-identity`, GET to `engine-read-current-binding`, materialization/discovery to `engine-materialize-current-binding`/`supra-consume-binding`. LKV types moved to the existing `engine-processing-event` CURRENT POM; its locator, READ storage, migrator and runtime remain provisional. The historical snapshot source contract moved to `engine-consume-projection-task`, with the loader/adapter still in PRIMARY. This placement preserves the TARGET Maven arcs: placing its existing Pot aggregate signature in `port-projection` would add an unapproved `port-projection → domain-pot` arc. The old POM was removed before `engine-projection-read` took the `engine-read-projection` artifactId. The final exact reader contains no LKV or Binding authority behavior.

- `TBD-LKV resolved: NO`; LKV promoted to firm READ persistence: **NO**. B15 remains until D.TBD-LKV. The firm `infra-persistence-read-jpa` contains Current Binding only.
- `TBD-E2U resolved: NO`; PRIMARY identity resolver still used where required: **YES** (`PotQueryController`/`ExternalIdentityResolverPort` and PRIMARY adapter). Exact projection path depends on E→U: **NO**. GET Pot remains explicitly dependent on the historical resolution path.
- `TBD-COMMAND-CONTRACT resolved: NO`. No Command or Registration engine/result split, final HTTP split, PRIMARY rename, or WP5 runtime cleanup was started.

## Bridges and dependency proof

- B6 removed: Task specialization moved from runtime to `engine-consume-projection-task`.
- B8 projector delegates and B12 old adapter identities were unnecessary because their consumers moved directly; no TARGET → legacy import was introduced.
- B9 Event locator POM, B10 Task POM and B14 Binding locator POM remain empty legacy → TARGET Maven facades for later removal at D.23, D.19 and D.23 respectively. B11 exact-read identity and B13 old Binding APIs are removed.
- B15 is the explicit CURRENT/provisional LKV hosting in `engine-processing-event` and `infra-read-persistence` until D.TBD-LKV. Existing `ExternalIdentityResolverPort`/PRIMARY adapter remain the E→U debt for later work.
- Dependency graph audit: one `pocoma-engine-read-projection` artifactId, zero duplicate child artifactIds, zero Maven cycles, zero temporary TARGET → legacy bridges.

## Verification

- Checkpoint A: `./mvnw -pl projector-pot -am test` and `./mvnw -pl runtime-task-consumption-worker -am test` green. The first sandboxed slice run could not reach Docker; the same canonical slice passed with Docker access.
- Checkpoint B: `./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker -am test` green.
- Checkpoint C: `./mvnw -pl runtime-task-consumption-worker,runtime-event-consumption-worker -am test` green.
- Checkpoint D: `./mvnw -pl runtime-task-consumption-worker,runtime-web-api,runtime-event-consumption-worker -am test` green. Initial `./mvnw test` exposed a stale architecture test import. After correction, `./mvnw -pl architecture-tests -am test` passed.
- Current Binding sub-checkpoint: `./mvnw -pl runtime-binding-consumption-worker,runtime-web-api -am test` green.
- Collision closure: `./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-web-api,runtime-latest-known-version-consumption-worker -am test` green; `./mvnw -pl architecture-tests -am test` green (102 architecture tests).
- Pure projector has only domain POM dependencies, no Spring/JPA/SQL/runtime/infra imports, and an explicit same-input determinism test. Existing Postgres tests prove exact @V without fallback, schema validation, root/artifact/failure semantics, Event ensure/retry, Task claim/finalize/late-commit, Binding revision/tombstone/divergence, and unchanged LKV behavior.
- Unexpected production slice crossing: none. No new SQL/migration or historical migration infrastructure change. Existing tests still exercise their historical fixtures because no trusted database baseline is declared by the policy.
- Final full reactor: `./mvnw test` green (63 reactor entries, including the parent POM and `architecture-tests`; 102 architecture tests, zero failures), after the last Maven arc cleanup. The delivery commits, push, final divergence and working-tree state are recorded in the final handoff.

## Audit post-WP2 et correction WP2.1

Le présent rapport conserve les noms et l’état livrés au commit `cfdaad6624c7934f12e47f371f71f49a4c36d244`. L’audit post-WP2 a révélé trois incohérences structurelles, sans échec fonctionnel : les deux noms d’infrastructure `*-jpa` désignaient des adapters `spring-jdbc`, des packages Java hérités contredisaient leur POM propriétaire, et `CurrentBindingProjectionPort` regroupait écriture et lecture tout en suggérant une appartenance erronée à la projection exacte `@V`. Le checkpoint [WP2.1](Modularity_WP2_1_Execution_Report.md) corrige ces trois points avant WP3, sans modifier le comportement ni les dettes TBD.
