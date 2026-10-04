# WP3 — Business WRITE execution report

## Baseline and pre-implementation inventory

At the initial audit, local `HEAD` and `origin/v2-make-it-pull` were both `0ecb42b54b5a6f51d691424ef682e6f91b21a6f2`; divergence was `0/0`, the working tree was clean, and there were no commits after WP2.1. `git fetch origin` succeeded before comparing refs.

The normative sources reviewed before implementation were the CURRENT audit, TARGET topology, migration plan, WP1, WP2, WP2.1 reports and `Reactor_Verification_Policy.md`. TARGET names, POM count, and TBD decisions are unchanged.

### Pot WRITE

- `engine-pot-command` owns ten Pot/Expense command intents, ten use case interfaces, the corresponding services, context and persistence ports, ten Command use case adapters, and Jackson payload decoders. Its adapters import the historical `engine-command` dispatch contract. A direct POM rename without separating these adapters would create `engine-consume-command → engine-write-pot → engine-consume-command`.
- `engine-core` owns `PotGlobalVersion`, four Pot/Expense snapshots, `UserContext`, `RecordedEvent`, `EventTraceMetadata`, `BusinessEventEnvelope`, two Pot-related exceptions, and legacy `PotPartitioner` at the starting HEAD.
- `domain-pot-policy` is a separate CURRENT POM with `CreatePotAuthorizationPolicy` and Pot authorization policy classes, although TARGET declares it `PACKAGE_ONLY` under `domain-pot`.
- `binding-pot-command-spring/PotCommandBindingConfiguration` is the concrete decoder/dispatcher/execution bean composition. It builds one `CommandDispatcher` from the ten adapters. `infra-persistence-jpa` owns Pot context, header, shares, version and outbox adapters and SQL.

### Command

- `engine-command` owns `Command`, `CommandId`, `CommandOutcome`, the recorded envelope/evidence, decoder and dispatch contracts, execution service, discovery/outcome/recording ports. `CommandId` and `CommandOutcome` have no final contract owner while `TBD-COMMAND-CONTRACT` remains open.
- `orchestrator-command-admission` owns `SubmitRecordedCommandService`, authentication evidence creation, expected-version admission and its input/output/ID generation ports; `supra-http-write-command` and `runtime-web-api` call it.
- `ExecuteRecordedCommandService` reloads the recorded Command, checks E+B Binding and evidence expiry, decodes and dispatches, appends events, and fences the observed Binding. Pot expected-version checks sit in the Pot WRITE services/adapters. `locator-consumption-command` owns candidate search, translation to Consumption provenance/outcome, terminal failure publication, fence recovery, classification and retry. `runtime-command-consumption-worker` assembles these with claim, transaction, polling and Spring lifecycle.
- `infra-persistence-jpa` owns PRIMARY recorded-command, discovery, outcome and event adapters. The existing Consumption transaction wrapper keeps claim separate from winning effect, provenance and CAS finalization. Command Result materialization/GET remains in `engine-command-result` and its separate runtime.

### Registration

- `contracts-registration` already owns `RegistrationRequest` and `RegistrationOutcome`. `engine-registration` currently owns `AdmitRegistrationService`, `ExecuteRegistrationService`, request/outcome stores and User-created fact port alongside out-of-scope Result materialization/read types.
- Admission records a request in its own `TransactionRunner` transaction. Execution reloads the request, checks an existing outcome, then calls Binding `acquireWithInitializer`; User creation and User-created fact append occur only inside the initializer. It inserts the outcome inside the fenced Consumption execute transaction. This is the existing no-orphan-User mechanism on Binding conflict.
- `runtime-registration-consumption-worker/RegistrationConsumptionLocator` owns candidate, issue, failure classification and retry, and directly names `JdbcRegistrationDiscovery`; its runtime configuration assembles execution, generic Consumption transaction wrappers and polling. `infra-persistence-jpa` owns PRIMARY request, outcome, discovery, User fact and Binding authority adapters. `runtime-web-api` owns the current admission wiring and HTTP controller. Registration Result materialization/read stays in `engine-registration` and its separate result runtime for WP4.

## Verification scope declared before production edits

- Expected production impact: Pot WRITE, Command admission/execution and specific Consumption glue, Registration admission/execution and specific Consumption glue, their PRIMARY adapters and affected runtime composition.
- Primary slice: `./mvnw -pl runtime-command-consumption-worker -am test`.
- Secondary slices: `./mvnw -pl runtime-registration-consumption-worker -am test`, `./mvnw -pl runtime-web-api -am test`, `./mvnw -pl runtime-binding-consumption-worker -am test`. Further consumer slices require an explicit boundary finding before changes.
- Forbidden impact: Result splits, final HTTP split, LKV, SQL, migrations, schema, final PRIMARY rename and WP5 runtime cleanup.
- Global architecture gate: REQUIRED at WP3 closure, `./mvnw -pl architecture-tests -am test`.
- Full reactor: REQUIRED at WP3 closure because seven POM boundaries materially restructure the graph, `./mvnw test`.
- Database proof: current-schema targeted Postgres Command and Registration tests, including conflict/rollback and late-commit guards. Historical migration proof is not planned for this structural step.
- Escalation: unexpected shared contract or production slice crossing; transaction behavior drift; Maven cycle; SQL/schema need; or need to settle a TBD/Results decision.

## Boundaries delivered

| WP3 POM / namespace | CURRENT source and responsibility | Direct internal dependencies after WP3 | Bridge |
| --- | --- | --- | --- |
| `engine-write-pot` / `engine.write.pot` | `engine-pot-command` raw Pot/Expense services, use cases, contexts and ports; `engine-core` snapshots, `UserContext` and Pot exceptions. Command payloads are translated to independent WRITE inputs. | `domain-pot`, `domain-authorization`, `domain-event` | B16 Spring dispatch retired; an input translator remains in Command consumption. Old Pot POM is an unused shell. |
| `engine-consume-command` / `engine.consume.command` | `engine-command` recorded model, decode/dispatch/execution, E+B fence, outcome ports; old Pot Command decoders/adapters; locator outcome/provenance and fence recovery. Claim remains in generic Consumption. | `domain-authorization`, `domain-event`, `domain-user-identity`, `domain-consumption`, `port-binding-authority`, `port-transaction`, `engine-consumption`, `engine-write-pot` | `CommandId`/`CommandOutcome` live here provisionally under open `TBD-COMMAND-CONTRACT`; no new contract POM. Old engine POM is an unused shell. |
| `supra-consume-command` / `supra.consume.command` | `locator-consumption-command` candidate/locator/key, classification and retry glue. | `engine-consume-command`, `orchestrator-consumption`, `engine-consumption` | Old locator POM is an unused shell. |
| `engine-admit-command` / `engine.admit.command` | `orchestrator-command-admission` authenticated evidence, Binding ID admission input, recording and application transaction. Existing HTTP adapter now calls this engine. | `port-transaction`, `contracts-authentication`, `domain-user-identity`, `engine-consume-command` | Old admission POM is an unused shell; HTTP rename remains WP4. |
| `engine-admit-registration` / `engine.admit.registration` | `engine-registration` request recording and commit-before-return transaction; accepts provider-neutral authenticated principal. | `contracts-authentication`, `contracts-registration`, `port-transaction` | Legacy Result store interface remains for WP4, implemented alongside the new recorder port by PRIMARY. |
| `engine-consume-registration` / `engine.consume.registration` | `engine-registration` request reload, duplicate outcome check, Binding `acquireWithInitializer`, User/fact/outcome write; owns discovery, request-reader, outcome and User-fact ports. | `contracts-registration`, `port-binding-authority` | B20 legacy execution facade delegates only legacy callers to TARGET; Result materialization stays legacy. |
| `supra-consume-registration` / `supra.consume.registration` | Runtime Registration candidate/locator, issue translation, failure policy; SQL discovery is behind the engine-owned port. | `engine-consume-registration`, `orchestrator-consumption`, `engine-consumption` | Runtime keeps only bean composition, generic Consumption transaction wrappers and polling. |

`domain-pot-policy` has been rehomed as packages of `domain-pot` and its POM removed. The concrete Pot Spring dispatch was moved from `binding-pot-command-spring` to `runtime-command-consumption-worker`; the runtime no longer depends on the old binding POM. A test-only equivalent configuration in Web assembles the cross-runtime contract test without a production runtime-to-runtime dependency. The runtime context test asserts exactly one `CommandDispatcher` and one `ExecuteRecordedCommandUseCase` bean.

## `engine-core` and compatibility

`PotGlobalVersion` moved to `domain-pot`; `UserContext`, four Pot/Expense snapshots and the two Pot-related exceptions moved to `engine-write-pot`. `RecordedEvent`, `EventTraceMetadata`, `BusinessEventEnvelope` and legacy `PotPartitioner` remain in `engine-core` for the later cleanup. No SQL wrapper was moved in WP3.

B16: old Spring binding retired in D.15. The input translator is owned by `engine-consume-command`, directs Command payloads to `engine-write-pot` inputs, and is required by their distinct responsibilities; it does not restore old dispatch ownership. B17/B18/B19: no facade was needed after direct caller rehome; the old POM shells are retained for WP5 structural demolition and have no production users. B20: owner `engine-registration`, direction legacy → `engine-consume-registration`, reason remaining legacy Result integration tests, removal with Registration Result extraction in WP4/WP5. A scan of the seven new POMs finds no direct TARGET → legacy dependency, and a DFS of the complete CURRENT Maven POM graph finds no cycle.

## Business invariants and scope

- Command admission separated: **YES**. Execution separated: **YES**. Expected version, E+B Binding fencing, late commit protection, claim before effect, winning effect + provenance + CAS atomicity, unique final outcome and retry use the same algorithms and transaction wrappers. `CommandId`/`CommandOutcome` now have only provisional physical ownership: `TBD-COMMAND-CONTRACT resolved: NO`.
- Registration admission separated: **YES**. Execution separated: **YES**. `acquireWithInitializer` still creates a User and User-created fact only after winning the Binding acquisition within the fenced execute transaction. The concurrent Binding conflict, no orphan User, retry and rollback Postgres tests remain in place. Registration Result split started: **NO**.
- No SQL, migration or schema file changed. PRIMARY remains `infra-persistence-jpa`. Final HTTP read/write split, Pot READ rename, Results splits and WP5 runtime demolition were not started. `TBD-E2U resolved: NO`; `TBD-LKV resolved: NO`; `TBD-COMMAND-CONTRACT resolved: NO`; `WP4 started: NO`.

## Verification and delivery

The declared Command, Registration and Web slices were run with their canonical Maven commands:

```text
./mvnw -pl runtime-registration-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker,runtime-web-api -am test
./mvnw -pl runtime-web-api -am test
./mvnw -pl runtime-binding-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```

The required architecture gate passed with 107 tests. The final full-reactor run passed all 69 modules. Its Postgres proofs include six `RegistrationAuthorityPostgresTest` cases (concurrent Binding conflict without an orphan User, retry and rollback), 25 `CommandConsumptionPostgresTest` cases, the multi-worker Command test, and three CCR end-to-end Command completion tests. All four new WP3 boundary tests passed. The Web slice passed 13 tests, including HTTP and PRIMARY composition. `git diff --check` passed.

The first unprivileged Registration slice could not access the Docker socket; rerunning with Docker access passed. The first full-reactor attempt failed in Web because the PRIMARY `target/classes` output had disappeared before Web context startup. A canonical Web slice regenerated the output and passed; rerunning the exact full-reactor command then passed. No production change or schema change was made to work around this transient build-output condition.

There was no unexpected production slice crossing. The global architecture gate and full reactor were required and run because WP3 changes seven Maven boundaries and transaction-sensitive Command/Registration flows. Postgres proofs used the current schema; the full reactor also ran its existing historical migration tests as part of the user-required global gate. SQL, migration and schema changes: **NO**.

Delivery commit, push and final divergence are recorded after Git closure.
