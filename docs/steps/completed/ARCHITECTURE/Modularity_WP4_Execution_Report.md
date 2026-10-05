# WP4 — Results & HTTP execution report

## Baseline and authority

`git fetch origin` succeeded. The starting local `HEAD` and `origin/v2-make-it-pull` were both `c2cd0241dde45762e911b124222aaddfd550f45e`; divergence was `0/0`, the working tree was clean, and there were no additional local commits. The initial unprivileged fetch was denied write access to `.git/FETCH_HEAD`; the authorized retry succeeded.

Before changing `app/`, the CURRENT audit, TARGET topology, migration plan, WP3 report and normative reactor verification policy were read. The Command/Registration Direct Result, READ, owner/opaque 404, Authentication and GET Pot sources were checked against the current implementation. TARGET remains normative. WP4 covers D.17, D.18 and D.20 only.

## Pre-implementation inventory

| Capability | CURRENT source, runtime and persistence | Protocol and ownership before WP4 |
| --- | --- | --- |
| Command Result | `engine-command-result` held `CommandResultSource`, immutable record/store, materializer and GET. `JdbcCommandResultSource` reloads the terminal Event, `CommandOutcome` and historical `recorded_commands.auth_issuer/auth_subject`; `JdbcCommandResultStore` uses `command_results`. `runtime-command-result-consumption-worker` held discovery locator/failure policy, Spring composition and polling. | `supra-http-read-query.CommandResultController` mapped GET to 200/404. Read service compares the stored E with authenticated E; no current Binding lookup. `engine-consume-command` provisionally owns `CommandId`/`CommandOutcome`. |
| Registration Result | `engine-registration` held Result immutable record/store, materializer and GET alongside legacy request/outcome stores and execution facade. `JdbcRegistrationResultDiscovery`, request/outcome adapters and `JdbcRegistrationResultStore` use PRIMARY tables. `runtime-registration-result-consumption-worker` held discovery locator/failure policy, Spring composition and polling. | `runtime-web-api.RegistrationResultController` mapped GET to 200/404. Historical owner comes from `RegistrationRequest.requesterExternalIdentity`, not CURRENT_BINDING. |
| HTTP WRITE | `supra-http-write-command` held Command DTO/controller. `runtime-web-api.RegistrationController` held Registration request validation/DTO/controller. Admission engines and `contracts-authentication` were already neutral. | `POST /api/v1/commands` and `POST /api/v1/registrations` return 202 after durable admission; Command/Registration result references are IDs in accepted response bodies, with no `Location` header. Invalid Command/request mapping is 400; missing/invalid authentication is 401. |
| HTTP READ | `supra-http-read-query` held Command Result, Current Binding and Pot GET controllers; `runtime-web-api` held Registration Result GET. `PotQueryController` invoked `ExternalIdentityResolverPort` in a transaction, translated authorities, then called `engine-pot-read`. Exact AUTH@V/READ_POT@V access was in `engine-read-projection`; resolver implementation was `JpaExternalIdentityResolverAdapter` on PRIMARY. | Command Result routes are `GET /api/v1/commands/{id}/result` and `/api/v1/command-results/{id}`; Registration Result is `GET /api/v1/registrations/{id}/result`; Pot is `GET /api/v1/pots/{id}?version=V`. Result absent or wrong owner is the same bodyless 404. Pot retains 200/400/404/409/503 mapping. The separate Current Binding GET remains in HTTP READ. |
| Authentication | `supra-authentication-spring-security` held JWT adaptation, argument resolution and resource server config; `runtime-web-api` depended on it. | `contracts-authentication` supplied the neutral authenticated principal; controllers used its attested E. |

## Declared verification scope before production edits

- Expected production impact: two Direct Result splits, capability-specific Result consumers, Pot read orchestration, HTTP read/write adapters, PRIMARY interface wiring and the three affected runtime compositions.
- Primary slice: WEB, `./mvnw -pl runtime-web-api -am test`.
- Secondary slices: Command Result, `./mvnw -pl runtime-command-result-consumption-worker -am test`; Registration Result, `./mvnw -pl runtime-registration-result-consumption-worker -am test`.
- Forbidden production impact: Command/Registration execution semantics, CURRENT_BINDING authority, ProjectionTask pipeline, SQL/schema/migrations, LKV, final PRIMARY persistence rehome and WP5 global runtime cleanup.
- Global architecture gate: **REQUIRED**, `./mvnw -pl architecture-tests -am test`.
- Full reactor: **REQUIRED**, `./mvnw test`, because WP4 closes the Results + Web structural split at D.18/D.20.
- Database proof: current-schema Postgres Result, HTTP and end-to-end tests. Historical migration compatibility is not a separate WP4 objective; the existing full-reactor tests may still exercise historical migration fixtures.
- Escalation conditions: unresolved Command contract/E→U decision, nonlocal Maven cycle, schema need, changed Result semantics, or WP5 cleanup need.

## Boundaries delivered

| TARGET module | Responsibility and source | Direct Result / runtime relationship |
| --- | --- | --- |
| `engine-read-command-result` | Moved immutable Command Result, store contract and GET owner check from `engine-command-result`. | Opaque missing/nonowner response; still imports provisional `CommandId`/`CommandOutcome` from `engine-consume-command`. |
| `engine-materialize-command-result` | Moved `CommandResultSource` and materialization policy. A neutral discovery/reload port gives the worker source metadata; SQL stays in PRIMARY. | Validates terminal Event, outcome and recorded Command identity, then insert-once through read-owned Result store. |
| `supra-consume-command-result` | Moved locator, candidate scan, reload invocation, issue/failure adaptation and retry classification from runtime. | Generic poll/orchestration remains in `orchestrator-poll-consumption`/`orchestrator-consumption`; runtime composes supra, engine and adapters. |
| `engine-read-registration-result` | Moved immutable Registration Result, store contract and GET owner check from `engine-registration`. | Owner is stored historical E. |
| `engine-materialize-registration-result` | Moved materialization policy; neutral source port reloads durable Request and Outcome through PRIMARY. | Validates source ID agreement and preserves Request E on immutable Result. |
| `supra-consume-registration-result` | Moved locator/candidate/reload/failure/retry adaptation from runtime. | Generic polling stays outside the supra. |
| `engine-read-pot` | Renamed/repackaged `engine-pot-read`; added E→U orchestration around exact Pot read. | `HTTP → engine-read-pot → existing ExternalIdentityResolverPort/PRIMARY → AUTH@V → READ_POT@V` through `engine-read-projection`. |
| `supra-http-write` | Renamed Command HTTP adapter; moved Registration POST controller, shared HTTP exception/status handler, error DTO and Command request-size filter from runtime. | HTTP authentication/DTO/status adaptation into admission engines only. |
| `supra-http-read` | Renamed read HTTP adapter and moved Registration Result GET from runtime. | Command/Registration Result owner-scoped GET and Pot/Current Binding protocol mapping into read engines. |
| `runtime-web-api` | Composes both HTTP supras, read/admission engines, concrete PRIMARY/projection adapters and Spring Security provider; keeps runtime configuration and observability filter. | Single Web runtime; provider classes moved into its runtime namespace. |

PRIMARY persistence is still `infra-persistence-jpa`. The legacy `engine-registration` shell retains only WP3 execution compatibility and historical request/outcome interfaces used by existing adapters/tests; it has **no Result materialization or GET policy**. Its remaining facade direction is `LEGACY → TARGET` and is due for WP5. No TARGET module imports this shell. `engine-command-result`, old HTTP POMs and the standalone auth supra POM were removed as named modules. No SQL, migration or schema file changed.

## Direct Result proofs

| Invariant | Command Result | Registration Result |
| --- | --- | --- |
| Immutable | **YES** — value record and `ON CONFLICT (command_id) DO NOTHING` followed by exact stored-value comparison. | **YES** — value record and `ON CONFLICT (request_id) DO NOTHING` followed by exact stored-value comparison. |
| 0..1 per source | **YES** — existing unique source key, replay and concurrent worker Postgres proofs. | **YES** — existing unique source key, replay, retry and parallel worker Postgres proofs. |
| Historical E owner | **YES** — recorded Command authentication E is copied to Result; GET compares stored E. | **YES** — Registration Request E is copied to Result; GET compares stored E. |
| Opaque 404 | **YES** — absent and nonowner return the same bodyless 404. | **YES** — absent and nonowner return the same bodyless 404. |
| CURRENT_BINDING dependency | **NO** in either read/materialization engine. | **NO**. |
| ProjectionTask dependency | **NO**; no Result task/projector path. | **NO**. |

The Postgres end-to-end scenario checks historical Result GET after E detach for **both** Results and 404 for another E. The Registration worker test also detaches and rebinds E to a replacement U, then reads the original Result unchanged. Pot remains an **Exact Projection @V** read; CURRENT_BINDING remains a **Current Mutable View**. These are separate from Direct Results.

## Bridges and deferred decisions

| Bridge | WP4 status | Direction / deadline |
| --- | --- | --- |
| B20 | Remaining legacy Registration execution facade and request/outcome compatibility interfaces; Result types extracted. | `LEGACY → TARGET`; remove during WP5 D.19 after remaining callers move. |
| B21 | Result locator/failure policy moved to supra; only worker composition/poll/lifecycle remains. | Runtime composition cleanup in WP5 D.23. |
| B22 | Removed with `engine-command-result` split; no facade required. | Complete. |
| B23 | Result locator/failure policy moved to supra; only worker composition/poll/lifecycle remains. | Runtime composition cleanup in WP5 D.23. |
| B24 | Old HTTP POM/package names removed; controllers are under the two new supras. | Complete; any further runtime composition cleanup stays WP5. |
| B25 | Historical E→U PRIMARY resolver now behind `engine-read-pot`. | OPEN until `TBD-E2U` decision; no CURRENT_BINDING substitution. |

`TBD-E2U = OPEN`. `TBD-COMMAND-CONTRACT = OPEN`. WP4 did not create `contracts-command`, move the provisional Command types or decide their final owner. The migration plan and debt register explicitly require **POST-WP4 — Resolve TBD-COMMAND-CONTRACT** after WP4 and before final consolidation, preferably before WP5 removes remaining legacy boundaries. **Checkpoint recorded: YES; resolved: NO.**

## Command contract consumer inventory for the post-WP4 checkpoint

The scan covers production Java sources under `app/*/src/main/java`; the type-owning module is included in each row. It is an input to the later decision, not that decision.

| Contract | Production module consumers |
| --- | --- |
| `CommandId` | `engine-admit-command` (submitted ID/generator), `engine-consume-command` (model, discovery, execution and ports), `engine-read-command-result` (GET/store), `infra-persistence-jpa` (outcome, source, store and recorded-command adapters), `runtime-web-api` (admission ID generator), `supra-consume-command` (locator/key), `supra-http-read` (GET path adaptation). |
| `CommandOutcome` | `engine-consume-command` (model, execution, publication/query), `engine-materialize-command-result` (source validation), `engine-read-command-result` (immutable Result and GET translation), `infra-persistence-jpa` (outcome/source/store adapters). |
| `RecordedCommand` | `engine-admit-command` (admission construction), `engine-consume-command` (model, execution and persistence port), `infra-persistence-jpa` (recording and row mapper). |
| Other shared Command values | `CommandType`: `engine-admit-command`, `engine-consume-command`, `infra-persistence-jpa`, `supra-http-write`. `CommandAuthenticationEvidence`: `engine-admit-command`, `engine-consume-command`, `infra-persistence-jpa`. `SubmittedCommand` remains within `engine-admit-command`. |
| Command Result identifiers/contracts | `CommandResultSource` and `CommandResultDiscovery`/candidate/cursor: `engine-materialize-command-result`, `supra-consume-command-result`, `infra-persistence-jpa`. `ImmutableCommandResult`/`CommandResultStore`: `engine-read-command-result`, `engine-materialize-command-result`, `infra-persistence-jpa`, Result worker composition. `GetCommandResult`/use case: `engine-read-command-result`, `supra-http-read`, Web read composition. Public response DTO: `supra-http-read`. |

## Verification and delivery

The declared multi-slice proof passed with:

```text
./mvnw -pl runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-web-api -am test
```

After the final HTTP handler/filter rehome, the canonical WEB slice also passed:

```text
./mvnw -pl runtime-web-api -am test
```

The mandatory global gates passed on the final code state:

```text
./mvnw -pl architecture-tests -am test
./mvnw test
```

The final architecture gate passed all WP4 boundary guards (5 tests), including namespace/POM checks, Direct Result exclusion from ProjectionTask and CURRENT_BINDING, engine exclusion from HTTP/runtime, and specific supra exclusion from concrete persistence. The full reactor passed all modules, including Web HTTP/Postgres tests, Command Result worker Postgres tests (2), Registration Result worker Postgres tests (3), and the Command completion end-to-end Postgres suite (3). `git diff --check` passed. The PRIMARY Result stores' insert-once behavior and historical E reads were verified without changing SQL or schema.

Additional commands actually executed during implementation: `./mvnw -pl runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-web-api -am -DskipTests compile` (passed); `./mvnw -pl engine-materialize-command-result,engine-materialize-registration-result,supra-http-read,supra-http-write -am test` (initial sandbox Mockito attachment failure); `./mvnw -pl runtime-command-result-consumption-worker -am test` (initial sandbox Docker denial, then compilation repair); `./mvnw -pl architecture-tests -am -DskipTests test` (compilation checks); `./mvnw -pl architecture-tests -am clean` (removed stale classes after package moves); and a focused `./mvnw -pl architecture-tests -am -Dtest=HexagonalArchitectureTest,Wa67BindingArchitectureTest,Wp4BoundaryTest,CommandCompletionE2EPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test` (passed). The first full architecture gate found stale package guards, an infra-to-supra port direction and a selective Spring test configuration missing the new Registration source adapter; these were corrected before the passing rerun. No semantic workaround was made for Docker or Mockito restrictions; the final required commands ran with the necessary access.

Observed production impact stayed in the declared Result/Web/Pot-read modules, PRIMARY adapter wiring and the three affected runtimes. A test-only import and test-scope POM reference in `runtime-task-consumption-worker` followed the Pot read rename; no PROJECTION production behavior changed. Unexpected **production** slice crossing: **NONE**. Global architecture gate: **RUN/PASS**. Full reactor: **RUN/PASS**, required by the WP4 structural milestone. Database proof: current-schema Postgres tests passed; the existing reactor also exercised historical migration fixtures, without a migration change. SQL/schema/migrations changed: **NO**. WP5 started: **NO**.

Delivery commit, push and final divergence are recorded in the final WP4 handoff.
