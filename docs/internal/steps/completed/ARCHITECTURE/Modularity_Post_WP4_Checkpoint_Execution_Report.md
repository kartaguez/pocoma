# POST-WP4.A/B/C — Execution report

**Baseline:** branch `v2-make-it-pull`, start HEAD and fetched `origin/v2-make-it-pull` both `12c9c66440717c4bf7cb6e7735272ce9aa0cbbc0`, divergence `0/0`, clean worktree. **WP5:** not started. **TBD-LKV:** OPEN.

## Declared verification boundary

The boundary was declared before editing `app/`, after reading the normative [Reactor Verification Policy](../../../testing/Reactor_Verification_Policy.md). Expected production impact: new `contracts-command`; Command admit/consume/Result materialization/read; Registration consume/Result materialization/read; `engine-read-pot`; Web composition; affected PRIMARY adapters and POMs. Primary slices: COMMAND, Registration, WEB, BINDING. Secondary slices: Command Result and Registration Result workers. Forbidden impact: SQL/schema/migration, LKV, WP5 demolition, TARGET→legacy dependencies. The global architecture gate and final full reactor were explicitly required because a new POM and shared type ownership changed Maven boundaries. Database proof: current Postgres behavior via Testcontainers, without an extra historical migration gate; the verification policy does not certify a trusted DB baseline.

## A — Ownership and Maven graph

| Concern | Before | After |
|---|---|---|
| CommandId, CommandType, RecordedCommand, TargetCommandEnvelope, CommandAuthenticationEvidence | `engine-consume-command` | `contracts-command`, the complete contents of that module |
| Command insertion/reload | shared CRUD `RecordedCommandPort` in consume | insert-only `RecordedCommandInsertionPort` in admit; reload-only `RecordedCommandPort` in consume; `JpaRecordedCommandAdapter` implements both |
| CommandOutcome | `engine-consume-command` | unchanged, execution-owned |
| Command published Result | `ImmutableCommandResult(owner, CommandOutcome)` in read | `ImmutableCommandResult(owner, PublishedCommandResult)`; read-owned Applied/Rejected/Failed, no consume dependency |
| RegistrationRequest | `contracts-registration` | unchanged; now the sole production type there |
| RegistrationOutcome | `contracts-registration` | `engine-consume-registration` |
| Registration published Result | `ImmutableRegistrationResult(owner, RegistrationOutcome)` | `ImmutableRegistrationResult(owner, PublishedRegistrationResult)`; read-owned Registered/Rejected, no consume dependency |

The Command contract owns only the stable admitted durable language needed to carry identity and payload through admission→persistence→consumption. It contains no outcome, Result, Spring, Security, JPA, JDBC, runtime, infra, HTTP DTO, consumption mechanics, binding authority, or execution policy. The Registration intake was not reshaped for visual symmetry. Its TARGET insert and reload ports were already separate. The Registration workflow and `bindings.acquireWithInitializer(...)` transaction order were not changed.

The materializers retain semantic dependencies on their consume engines. Command validates discovered, terminal, outcome and recorded IDs, and terminal Event type, before converting to the published Result. Registration validates discovered Request ID, Request ID and outcome ID, then converts using the historical E from the Request. The two immutable stores retain insert-once/replay equality and reject divergent replay; GET preserves opaque 404 and historical owner E, independent of CURRENT_BINDING and ProjectionTask.

For the full reactor POM inventory, using one XML counting method on both revisions, the graph changed from **72 modules / 346 direct internal arcs** to **73 modules / 354 direct internal arcs**, with no cycle. This count includes legacy modules and differs from older TARGET-only graph tables. Relevant changed arcs: new `admit/consume/read Command Result → contracts-command`; removed `admit→consume Command` and `read Command Result→consume Command`; added `materialize Registration Result→consume Registration`; removed `read Registration Result→contracts-registration`; added `read Pot→read Current Binding`; removed `read Pot→port-transaction`. No TARGET→legacy arc was added. The only new module is `contracts-command`; no Maven module was removed.

## B — GET Pot E→U

Final path: authenticated E → `engine-read-pot` → `GetCurrentBindingUseCase.getAttached(E)` in `engine-read-current-binding` → `CurrentBindingReadPort` → READ `CURRENT_BINDING` → projected U → AUTH@V → READ_POT@V. ATTACHED returns U; DETACHED and absence yield no U and the existing opaque 404. GET Pot does not query PRIMARY to distinguish unprojected from unbound. The obsolete production `ExternalIdentityResolverPort` and `JpaExternalIdentityResolverAdapter` were removed; tests of the PRIMARY table now use its repository directly. No Binding authority, PRIMARY adapter or new E2U port was introduced into the READ engine.

The convergence window is contractual: a PRIMARY detach may have committed while CURRENT_BINDING still exposes ATTACHED U1; GET Pot may then read with U1 if AUTH@V authorizes U1. After the Binding worker projects DETACHED, U1 is unavailable; after a converged rebind, U2 is used. `PotBindingConvergencePostgresTest` commits attach/detach/rebind in PRIMARY and controls READ materialization to prove all four stages. `ReadPotServiceTest` also combines the projected E→U selection with actual AUTH@V/READ_POT@V interpretation, including authorized U1/U2 and refusal after DETACHED. This does not create an immediate revocation fence; no maximum lag is claimed.

## C — Revision semantics

**C2, monotonic maximum convergence**, not strict C1. The complete reasoning and scenario matrix are in [the revision audit](Modularity_Post_WP4_Current_Binding_Revision_Audit.md). Authority revisions are allocated contiguously under a per-E stream lock and facts are appended in the same transaction. Discovery ordering does not serialize execution; its cursor is invocation-local. Claim/retry/restart/multiple workers can execute facts out of order. READ applies any greater revision, marks a lower one stale, and requires identical payload plus source event for a duplicate at the same current revision. Every fact is a complete state snapshot. Skipping `R+1` when `R+2` arrives first can omit an intermediate visible state, but cannot leave an incorrect final state **if the latest fact is eventually processed**. The adapter algorithm was not changed. The earlier documentation claiming contiguous READ materialization was corrected. Stopped workers or unrepaired terminal failures can delay convergence indefinitely; this operational precondition is not a reason to impose an artificial R+1 gate.

## Verification execution history

Commands below were run from `app/`. The first `git fetch origin` needed filesystem escalation because `.git/FETCH_HEAD` is outside the ordinary write sandbox; the subsequent fetch succeeded. Initial compilation exposed test fixtures that still built execution outcomes in READ models; these fixtures were migrated. The first non-escalated global gate could not contact Docker, and one escalated gate overlapped another Maven invocation on shared `target/` directories; both environmental attempts were retried sequentially. The final results below are from sequential runs with Docker access. No remaining test failure was suppressed.

- `./mvnw -pl runtime-command-consumption-worker,runtime-registration-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am -DskipTests compile` — PASS.
- The same six anchors with `-am -DskipTests test` — PASS after adapting test source types.
- `./mvnw -pl engine-read-pot,engine-materialize-registration-result -am test` — PASS.
- The declared six-anchor `-am test` command — PASS.
- `./mvnw -pl runtime-web-api -am -Dtest=PotBindingConvergencePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS.
- `./mvnw -pl runtime-command-result-consumption-worker,runtime-web-api -am test` — PASS after the final published-model shape validation.
- `./mvnw -pl architecture-tests -am test` — PASS on the final production diff.
- `./mvnw test` — PASS on the final production diff.

## Verification and closure record

- Declared slice command: `./mvnw -pl runtime-command-consumption-worker,runtime-registration-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am test` — **PASS**, including Postgres Registration no-orphan-user, Command fencing/replay, both Result runtimes, and Binding retry/multi-worker.
- Focused convergence command: `./mvnw -pl runtime-web-api -am -Dtest=PotBindingConvergencePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test` — **PASS**.
- Follow-up Results/WEB command after published-shape validation: `./mvnw -pl runtime-command-result-consumption-worker,runtime-web-api -am test` — **PASS**.
- Architecture gate: `./mvnw -pl architecture-tests -am test` — **PASS**.
- Full reactor: `./mvnw test` — **PASS** (73 reactor modules).
- Guards: `PostWp4OwnershipArchitectureTest` covers contract allowlist and exclusions, phase ownership, admit/read dependency removal, and READ Pot/Result forbidden arcs. Existing WP2–WP4 and hexagonal guards remain in the gate.
- SQL/schema/migration production changes: **none**. Unexpected production slice crossing: **none**. No Maven cycle. No TARGET→legacy dependency added.
- Removed bridge: PRIMARY E→U resolver port and adapter. Retained: legacy `engine-registration` and other WP5 shells/locators; no broad WP5 demolition.
- TBD-COMMAND-CONTRACT: **RESOLVED** after ownership, Result, guard and reactor proofs. TBD-E2U: **RESOLVED** after GET Pot convergence and C2 proofs. TBD-LKV: **OPEN**.
- Delivery commit/push and exact final HEAD, remote divergence and worktree are recorded in the delivery response after Git finalization.
