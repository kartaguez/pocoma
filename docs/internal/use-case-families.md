# Use-case families

Pocoma separates functional application behavior from durable processing and incoming adapters.
Primary mutations are invoked exclusively from durable Command consumption. HTTP WRITE admission
performs AuthN, structural validation and durable capture only: it never reads primary business
state, performs business AuthZ, consults READ to decide admission, or calls a mutation use case.
HTTP READ builds responses exclusively from READ/projections and never falls back to primary state.

## Business commands — `engine-pot-command`

The command records under `engine.port.in.command.intent` are typed business intentions such as
`CreatePotCommand` or `CreateExpenseCommand`. They are inputs to business use cases; they are not
durable queue records.

Command use cases may load and persist Pot state, enforce business policies and optimistic
concurrency, and append immutable business events. They must not know about polling, workers,
claims, leases, processing statuses, or command-queue persistence.

The ten specialized `*UseCase` interfaces are business inbound ports. Durable Command adapters
depend on those ports and keep the concrete services package-private. `CommandIntent`, the generic
`ExecuteCommandUseCase`/`ExecuteCommandService` router, and the synchronous transactional wrappers
were specific to the retired write path and no longer exist.

## Durable commands — `engine-command`

`engine-command` owns the provider-neutral `RecordedCommand`: durable id and type, opaque payload,
submission time, captured `ExternalIdentity`, presented `BindingId` and immutable authentication
evidence. `infra-persistence-jpa` stores that envelope
in `recorded_commands` through explicit JDBC. The source row has no processing lifecycle and an
insert never creates a consumption slot.

Command discovery is a short best-effort read ordered by PostgreSQL on
`(submittedAt, commandId)`. It may return the same candidate to multiple workers.
`locator-consumption-command` asks `engine-consumption.acquire()` to lazily create or claim
`COMMAND[commandId] / COMMAND_PROCESSOR[]`; only that operation is authoritative. It reloads and
decodes the Command only after acquisition, adapts success/rejection and classifies technical
failures. `runtime-command-consumption-worker` runs this path with the generic polling worker.

`orchestrator-command-admission` exposes the separate asynchronous intake use case. The Spring
supra authenticates the bearer token, adapts it to `AuthenticatedExternalPrincipal`, and the
orchestrator captures E, a structurally valid B and the required authentication evidence without
resolving a User or deciding AuthZ. A `202`
means durable acceptance only; it never invokes this consumption path or a Pot use case.

After fenced acquisition and authoritative reload, the worker resolves current `(E,B) -> U` on the
User/Identity primary, evaluates capabilities and business authorization, then reads/mutates Pot
state. Resolution and mutation share a transaction boundary that keeps B current through commit.

## Canonical exact projection reads

The legacy `engine-query` use cases and their HTTP adapters were removed by PCL.4. Exact reads remain
available through the projection read boundary: `ReadPotService` requests an exact artifact through
`ExactProjectionReadService` and interprets it with `ReadPotInterpreter`. This path reads only the
canonical projection store and does not recreate a Query facade or fall back to primary state.

## Projection task materialization — EPT

The Event worker discovers durable envelope metadata without decoding the payload. The exhaustive
`ProjectionMaterializationPolicy` maps each Event type to its canonical projection types. For every
route, generic Consumption owns the exact identity
`EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]`, and finalization idempotently ensures
the corresponding `projection_tasks` row. No production Event path writes `tasks_4_pipeline`.

## ProjectionTask execution — `engine-projection-task`

The canonical runtime discovers exact rows in `projection_tasks`, acquires
`PROJECTION_TASK/[ProjectionKey] × PROJECTION_EXECUTOR/[projectionType]`, then delegates to
`ProjectionTaskConsumptionService` and `ProjectionEngineService`. READ_POT and POT_BALANCES load
their historical inputs and publish only through `ProjectionWritePort` to the canonical store.

## Durable consumption domain — `domain-consumption`

The durable consumption domain owns `ConsumptionKey`, slots, `ClaimToken`, leases, statuses,
failures, and their invariants. It contains no use case, persistence concern, ordering,
segmentation, or worker orchestration.

`ConsumptionSlot` is the authoritative processing lifecycle. `RecordedCommand` carries no status.
Event, ProjectionTask and Command discoveries consult the generic lifecycle only as a best-effort prefilter
before authoritative acquisition.

Ordering and segmentation are technical processing concerns. Chaque ordre appartient désormais à
son module spécialisé : `locator-consumption-command`, `engine-processing-event` ou le store
canonique ProjectionTask. Static segmentation uses a stable
`PartitionHash` and a configured `WorkerSegment`. Commands with
a Pot id use the Pot id as their partition key. Commands without a Pot id are unsegmented and will
later be made eligible to every Command worker segment; atomic claiming will select a single
owner. Event and ProjectionTask consumers use their canonical persisted partition hashes.

Claim ordering is also explicit and is independent from segmentation:

- target Recorded Commands are ordered by PostgreSQL on `(submittedAt, commandId)` only;
- EventConsumptions are ordered by `(event.version(), recordedAt, eventId)`;
- ProjectionTasks are scanned by their canonical ordering key. `targetVersion` identifies an exact
  historical input ; it is not an ordering or serialization constraint.

The identifier is a deterministic tie-breaker. These rules guarantee claim priority among eligible
items, not completion order between concurrent workers.

## Durable consumption use cases — `engine-consumption`

Generic consumption use cases own only try-acquire/complete/fail/release for an opaque
`ConsumptionKey`. They do not select work and know neither Command, Event, Task, Pot nor Pipeline.
The specialized processing engines compose these use cases with their source-specific selection,
ordering, segmentation and durable-object transitions.

## Incoming adapters

A supra is passive and reacts to an external invocation, for example HTTP or a listener. A worker
is active and owns a polling loop. Both invoke engine use cases; neither may contain business or
pipeline-specific logic.

`supra-worker-event` orchestre une seule consommation Event à la fois pour un pipeline/version et
un segment. Il appelle la création idempotente de Tasks puis le lifecycle Event ; il ne voit jamais
les consommations déjà terminales et ne modifie aucun statut sur l'Event source.

`ProjectionTaskConsumptionOrchestrator` propose les candidates depuis `projection_tasks`. Après
acquisition, `ProjectionTaskConsumptionService` exécute le producteur canonique et finalise sous le
Claim courant. `ConsumptionPollingWorker` fournit uniquement la boucle générique.

`locator-consumption-command` applique le même orchestrateur générique à la source
`recorded_commands`. Un Search conserve son cursor uniquement pendant son cycle. Les erreurs SQL
transitoires connues sont retryables ; les erreurs de configuration, invariants et runtimes
inconnues sont terminales. `LostClaimException` contourne toujours le classifier et le failure
handler.

The concepts are deliberately distinct:

- Command: typed business intention;
- durable Command envelope: persisted request awaiting execution;
- Event: immutable business fact with no consumption status;
- EventConsumption: independent processing state for one Event and one Pipeline version;
- Task: autonomous pipeline work item;
- Claim: temporary ownership represented by a fencing token.

## Use-case inventory at the end of step 1

`Target` means that the contract already expresses its intended responsibility. `Legacy` means
that it remains callable only to keep the current workers operational.

| Use case | Family | Input | Output | Outgoing ports | Transaction | Current callers | State / migration |
|---|---|---|---|---|---|---|---|
| `CreatePotUseCase` | Command | `UserContext`, `CreatePotCommand` | `PotHeaderSnapshot` | Pot header/version, events | Winning consumption tx | durable Command adapter | Target business port |
| `CreateExpenseUseCase` | Command | context, `CreateExpenseCommand` | `ExpenseSharesSnapshot` | Pot/expense state, events | Winning consumption tx | durable Command adapter | Target business port |
| `DeletePotUseCase` | Command | context, `DeletePotCommand` | `PotHeaderSnapshot` | Pot state, events | Winning consumption tx | durable Command adapter | Target business port |
| `DeleteExpenseUseCase` | Command | context, `DeleteExpenseCommand` | `ExpenseHeaderSnapshot` | Pot/expense state, events | Winning consumption tx | durable Command adapter | Target business port |
| `UpdatePotDetailsUseCase` | Command | context, typed command | `PotHeaderSnapshot` | Pot state, events | Winning consumption tx | durable Command adapter | Target business port |
| `AddPotShareholdersUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Winning consumption tx | durable Command adapter | Target business port |
| `UpdatePotShareholdersDetailsUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Winning consumption tx | durable Command adapter | Target business port |
| `UpdatePotShareholdersWeightsUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Winning consumption tx | durable Command adapter | Target business port |
| `UpdateExpenseDetailsUseCase` | Command | context, typed command | `ExpenseHeaderSnapshot` | Pot/expense state, events | Winning consumption tx | durable Command adapter | Target business port |
| `UpdateExpenseSharesUseCase` | Command | context, typed command | `ExpenseSharesSnapshot` | Pot/expense state, events | Winning consumption tx | durable Command adapter | Target business port |
| `TryAcquireConsumptionUseCase` | Consumption | consumption key, worker, lease | acquired, busy, already completed or already failed | `ClaimPort` | Decorator | Processing engines | Target |
| `CompleteConsumptionUseCase` | Consumption | consumption key, token | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `FailConsumptionUseCase` | Consumption | consumption key, token, failure | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `ReleaseConsumptionUseCase` | Consumption | consumption key, token | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `ClaimNextEventUseCase` | Event processing | worker, lease, segment, pipeline | optional recorded event and claim | read-only `EventPort`, generic acquisition | Decorator | Future Event worker | Target |
| `CompleteEventProcessingUseCase` | Event processing | pipeline, event id, token | `ConsumptionOutcome` | generic completion | Decorator | Future Event worker | Target |
| `FailEventProcessingUseCase` | Event processing | pipeline, event id, token, failure | `ConsumptionOutcome` | generic failure | Decorator | Future Event worker | Target |
| `ReleaseEventProcessingUseCase` | Event processing | pipeline, event id, token | `ConsumptionOutcome` | generic release | Decorator | Future Event worker | Target |
| `ClaimNextTaskUseCase` | Task processing | worker, lease, segment, pipeline | optional recorded task and claim | `TaskPort`, generic acquisition | Decorator | Future Task worker | Target |
| `CompleteTaskProcessingUseCase` | Task processing | task id, token | `ConsumptionOutcome` | `TaskPort`, generic completion | Generic lifecycle transaction, then best-effort materialization | Future Task worker | Target |
| `FailTaskProcessingUseCase` | Task processing | task id, token, failure | `ConsumptionOutcome` | `TaskPort`, generic failure | Generic lifecycle transaction, then best-effort materialization | Future Task worker | Target |
| `ReleaseTaskProcessingUseCase` | Task processing | task id, token | `ConsumptionOutcome` | generic release | Decorator | Future Task worker | Target |
| `ExecuteTaskUseCase` | Task execution | typed payload, pipeline, type | none | Handler-specific use case | Handler owns it | Direct/supra, legacy bridge | Target |
| `ExecutePipelineTaskUseCase` | Task execution legacy | durable `PipelineTask` | none | Legacy strategy registry | Worker flow | Current task worker | Legacy; remove with task worker |
| `BuildProjectionTasksUseCase` | Projection legacy | outbox envelope | none | projection task/event ports | Service-specific | Legacy projection flow | Legacy; replace by task creation |
| `ExecuteProjectionTasksUseCase` | Projection legacy | Pot/version command | none | projection task/event ports | Service-specific | Legacy projection flow | Legacy; replace by typed task execution |
| `CalculatePotBalancesAtVersionUseCase` | Canonical POT_BALANCES producer | Pot id, exact target version | `PotBalances` | `HistoricalPotBalanceSourcePort` | Service-owned exact reconstruction | `PotBalancesProjectionInputLoader` | Target; independent of mutable Balance state |

## Runtime paths

The Command write path is closed and operational:

```text
HTTP admission -> RecordedCommand -> generic polling/consumption
               -> durable Command adapter -> business use-case port
               -> Pot state + BusinessEvent + terminalization
```

There is no synchronous HTTP mutation route or separate Command processing lifecycle. The
remaining transitional paths below belong to Event, Task, projection and the future read-side
redesign; they are deliberately not changed by the write-side closure.

The canonical task path is:

```text
projection_tasks -> generic Task consumption
  -> ProjectionTaskConsumptionOrchestrator
  -> ProjectionEngineService
  -> READ_POT / POT_BALANCES producer
  -> canonical projection store
```

The current Event path is canonical:

```text
business_event_outbox -> metadata-only discovery -> ProjectionMaterializationPolicy
  -> Consumption EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]
  -> projection_tasks
```

PCL.6 removed the mutable Balance worker and persistence branch. `POT_BALANCES` reconstructs its
input at the requested historical version and never consumes `pot_balance_*` runtime state.

## Result of step 2

The domain modules now have explicit ownership: Pot and its events, Pot authorization policies,
the Balance projection calculation, pipeline identity, typed task payloads, and generic durable
consumption. `engine-core` contains only shared application contracts plus explicitly isolated
legacy types.

Functional engines own business Commands, exact projection reads, typed Event-to-Task planning,
typed Task execution, and Balance projection. Their ports are consumer-oriented: exact reads use
`ProjectionReadPort`; Balance calculation uses `PotBalanceProjectionPort` and
`PotShareholdersProjectionPort`; Commands use their writable `PotShareholdersPort` and the typed
`BusinessEventAppendPort`.

`engine-consumption` owns generic acquire/complete/fail/release operations. The three specialized
processing engines add only source selection, ordering, segmentation, consumption-key construction
and durable Command/Task terminal transitions. They never execute the business Command, create
Tasks from an Event, or execute a typed Task.

The deterministic concurrent tests added in step 2.9 prove the in-memory contracts for lazy slot
creation, fencing, terminal states, Command/Task mono-consumption and Event multi-consumption.
They do not prove SQL atomicity: CAS and rollback guarantees must be verified against the future
PostgreSQL `ClaimPort` adapter.

## Result of step 3

The target Command, Event and Task workers now share the sequential pull loop while retaining
explicit orchestration. Command and Task protect committed effects with execution guards; Event
protects task creation by `(pipelineId, pipelineVersion, eventId)`. A completion loss is recovered
after reclaim without repeating the protected effect.

Each worker processes one item per iteration and one active item per instance, while independent
instances can run concurrently. Lease warnings and overruns are observed without heartbeat or
forced interruption. Command/Task reconciliation remains in their processing engines and Event
never receives a global consumption status. See `docs/testing/worker-contract-matrix.md`.
