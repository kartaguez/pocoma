# Use-case families

Pocoma separates functional application behavior from durable processing and from incoming
adapters. The same functional use case must remain callable from either a reactive supra in push
mode or an autonomous worker in pull mode.

## Business commands — `engine-command`

The command records under `engine.port.in.command.intent` are typed business intentions such as
`CreatePotCommand` or `CreateExpenseCommand`. They are inputs to business use cases; they are not
durable queue records.

Command use cases may load and persist Pot state, enforce business policies and optimistic
concurrency, and append immutable business events. They must not know about polling, workers,
claims, leases, processing statuses, or command-queue persistence.

Generic incoming adapters can invoke `ExecuteCommandUseCase`, which routes a `CommandIntent` to
the matching specialized use case. Typed incoming adapters remain free to invoke the specialized
use case directly. The routing facade adds neither business logic nor a transaction boundary: its
delegates are the same transactionally configured use cases exposed to direct callers.

## Queries — `engine-query`

Query use cases synchronously read Pot state and projections through read ports. They are separate
from durable asynchronous processing and must not depend on command, event-consumption, or task
workers.

## Task creation — `engine-task-creation`

Pure task planning receives a typed `BusinessEvent` and a `PipelineDefinition` and produces zero
to many autonomous `TaskDescriptor` objects. Durable creation decorates the same event with a
`RecordedEvent` carrying its outbox identity, timestamp, and optional trace metadata, then persists
the plan idempotently for `(pipelineId, pipelineVersion, eventId)` — including empty plans.

JSON and `BusinessEventEnvelope` remain infrastructure/legacy-outbox representations. The current
`engine-task-materialization` module and worker remain temporarily active as the runtime bridge;
they will be retired only after the new event-consumption worker is wired.

An Event has no consumption status. Each interested pipeline has an independent EventConsumption,
identified by `(pipelineId, pipelineVersion, eventId)`. Claiming or completing that consumption is
technical processing and is not part of the Event-to-Task use case.

## Task execution — `engine-task-execution`

Typed task execution receives `ExecuteTaskInput`, resolves the handler identified by pipeline id,
pipeline version, and task type, then invokes the pipeline-specific functional use case. Its
`TaskPayload` contains only the work to perform: no durable id, claim, lease, trace, status,
or JSON. Worker bindings affect only pull eligibility and never direct functional execution.

The current worker-facing `PipelineTask` route remains transitional. Its runtime strategy decodes
the persisted JSON into a typed payload and delegates to `ExecuteTaskUseCase`; completion, failure,
retry, polling, and worker lifecycle remain outside the typed engine.

## Durable consumption domain — `domain-consumption`

The durable consumption domain owns `ConsumptionKey`, slots, `ClaimToken`, leases, statuses,
failures, and their invariants. It contains no use case, persistence concern, ordering,
segmentation, or worker orchestration.

`ConsumptionSlot` is the authoritative processing lifecycle. Command and Task statuses are
reconcilable durable materializations: a terminal slot may temporarily coexist with a READY or
PENDING source row, but the processing services always transition the slot first and repair that
lag while selecting subsequent work. A terminal durable status with a READY slot is never
produced by these services.

Ordering and segmentation are technical processing concerns. Chaque ordre appartient désormais à
son module spécialisé : `engine-processing-command`, `engine-processing-event` ou
`engine-processing-task`. Static segmentation uses a stable
`PartitionHash` and a configured `WorkerSegment`. Commands with
a Pot id use the Pot id as their partition key. Commands without a Pot id are unsegmented and will
later be made eligible to every Command worker segment; atomic claiming will select a single
owner. EventConsumptions and Tasks use `(pipelineId, potId)` as their partition key.

Claim ordering is also explicit and is independent from segmentation:

- Commands are ordered by `(createdAt, commandId)` only;
- EventConsumptions are ordered by `(event.version(), recordedAt, eventId)`;
- Tasks are ordered by `(targetVersion, createdAt, taskId)`.

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
| `CreatePotUseCase` | Command | `UserContext`, `CreatePotCommand` | `PotHeaderSnapshot` | Pot header/version, events | Decorator | HTTP, command router | Target |
| `CreateExpenseUseCase` | Command | context, `CreateExpenseCommand` | `ExpenseHeaderSnapshot` | Pot/expense state, events | Decorator | HTTP, command router | Target |
| `DeletePotUseCase` | Command | context, `DeletePotCommand` | `PotHeaderSnapshot` | Pot state, events | Decorator | HTTP, command router | Target |
| `DeleteExpenseUseCase` | Command | context, `DeleteExpenseCommand` | `ExpenseHeaderSnapshot` | Pot/expense state, events | Decorator | HTTP, command router | Target |
| `UpdatePotDetailsUseCase` | Command | context, typed command | `PotHeaderSnapshot` | Pot state, events | Decorator | HTTP, command router | Target |
| `AddPotShareholdersUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Decorator | HTTP, command router | Target |
| `UpdatePotShareholdersDetailsUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Decorator | HTTP, command router | Target |
| `UpdatePotShareholdersWeightsUseCase` | Command | context, typed command | `PotShareholdersSnapshot` | Pot state, events | Decorator | HTTP, command router | Target |
| `UpdateExpenseDetailsUseCase` | Command | context, typed command | `ExpenseHeaderSnapshot` | Pot/expense state, events | Decorator | HTTP, command router | Target |
| `UpdateExpenseSharesUseCase` | Command | context, typed command | `ExpenseSharesSnapshot` | Pot/expense state, events | Decorator | HTTP, command router | Target |
| `ExecuteCommandUseCase` | Command routing | `ExecuteCommandInput` | specialized result | None directly | Delegate owns it | Generic incoming adapters | Target |
| `ListUserPotsUseCase` | Query | `UserContext` | pot headers | `PotQueryPort` | Read decorator | HTTP | Target |
| `GetPotUseCase` | Query | context, `GetPotQuery` | `PotViewSnapshot` | `PotQueryPort` | Read decorator | HTTP | Target |
| `ListPotExpensesUseCase` | Query | context, typed query | expense headers | Pot/expense query ports | Read decorator | HTTP | Target |
| `GetExpenseUseCase` | Query | context, typed query | `ExpenseViewSnapshot` | Pot/expense query ports | Read decorator | HTTP | Target |
| `GetPotBalancesUseCase` | Query | context, typed query | `PotBalancesSnapshot` | Pot query, balances | Read decorator | HTTP | Target |
| `ListUserPotBalancesUseCase` | Query | context, typed query | user balances | Pot query, balances | Read decorator | HTTP | Target |
| `TryAcquireConsumptionUseCase` | Consumption | consumption key, worker, lease | acquired, busy, already completed or already failed | `ClaimPort` | Decorator | Processing engines | Target |
| `CompleteConsumptionUseCase` | Consumption | consumption key, token | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `FailConsumptionUseCase` | Consumption | consumption key, token, failure | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `ReleaseConsumptionUseCase` | Consumption | consumption key, token | `ConsumptionOutcome` | `ClaimPort` | Decorator | Processing engines | Target |
| `ClaimNextCommandUseCase` | Command processing | worker, lease, segment | optional durable command and claim | `CommandPort`, generic acquisition | Decorator | Future Command worker | Target |
| `CompleteCommandProcessingUseCase` | Command processing | command id, token | `ConsumptionOutcome` | `CommandPort`, generic completion | Generic lifecycle transaction, then best-effort materialization | Future Command worker | Target |
| `FailCommandProcessingUseCase` | Command processing | command id, token, failure | `ConsumptionOutcome` | `CommandPort`, generic failure | Generic lifecycle transaction, then best-effort materialization | Future Command worker | Target |
| `ReleaseCommandProcessingUseCase` | Command processing | command id, token | `ConsumptionOutcome` | generic release | Decorator | Future Command worker | Target |
| `ClaimNextEventUseCase` | Event processing | worker, lease, segment, pipeline | optional recorded event and claim | read-only `EventPort`, generic acquisition | Decorator | Future Event worker | Target |
| `CompleteEventProcessingUseCase` | Event processing | pipeline, event id, token | `ConsumptionOutcome` | generic completion | Decorator | Future Event worker | Target |
| `FailEventProcessingUseCase` | Event processing | pipeline, event id, token, failure | `ConsumptionOutcome` | generic failure | Decorator | Future Event worker | Target |
| `ReleaseEventProcessingUseCase` | Event processing | pipeline, event id, token | `ConsumptionOutcome` | generic release | Decorator | Future Event worker | Target |
| `ClaimNextTaskUseCase` | Task processing | worker, lease, segment, pipeline | optional recorded task and claim | `TaskPort`, generic acquisition | Decorator | Future Task worker | Target |
| `CompleteTaskProcessingUseCase` | Task processing | task id, token | `ConsumptionOutcome` | `TaskPort`, generic completion | Generic lifecycle transaction, then best-effort materialization | Future Task worker | Target |
| `FailTaskProcessingUseCase` | Task processing | task id, token, failure | `ConsumptionOutcome` | `TaskPort`, generic failure | Generic lifecycle transaction, then best-effort materialization | Future Task worker | Target |
| `ReleaseTaskProcessingUseCase` | Task processing | task id, token | `ConsumptionOutcome` | generic release | Decorator | Future Task worker | Target |
| `PlanTasksForEventUseCase` | Task creation | typed event, pipeline | `TaskCreationPlan` | None | None | Direct/supra, durable facade | Target |
| `CreateTasksForEventUseCase` | Task creation | recorded event, pipeline | `TaskCreationResult` | `TaskCreationPort` | Decorator | Future event worker | Target |
| `ExecuteTaskUseCase` | Task execution | typed payload, pipeline, type | none | Handler-specific use case | Handler owns it | Direct/supra, legacy bridge | Target |
| `MaterializeTasksUseCase` | Event-to-task legacy | outbox envelope | materialization result | materialization persistence | Service-specific | Current materialization worker | Legacy; remove with event worker |
| `ExecutePipelineTaskUseCase` | Task execution legacy | durable `PipelineTask` | none | Legacy strategy registry | Worker flow | Current task worker | Legacy; remove with task worker |
| `BuildProjectionTasksUseCase` | Projection legacy | outbox envelope | none | projection task/event ports | Service-specific | Legacy projection flow | Legacy; replace by task creation |
| `ExecuteProjectionTasksUseCase` | Projection legacy | Pot/version command | none | projection task/event ports | Service-specific | Legacy projection flow | Legacy; replace by typed task execution |
| `ComputePotBalancesUseCase` | Projection function | Pot id, target version | `PotBalances` | `PotBalanceProjectionPort`, `PotShareholdersProjectionPort` | Decorator | Typed balance handler | Target; calculation model in `domain-projection-balance` |

## Transitional runtime paths

The future command path is now defined at application level but is not wired to persistence yet:

```text
Command worker -> ClaimNextCommandUseCase -> ExecuteCommandUseCase -> complete/fail/release
```

The current task path is intentionally bridged:

```text
Task worker
  -> ExecutePipelineTaskUseCase (legacy)
  -> ComputeBalancesPipelineTaskExecutionStrategy (JSON adapter)
  -> ExecuteTaskUseCase (typed target)
  -> ExecuteBalanceProjectionTaskHandler
  -> ComputePotBalancesUseCase
```

It becomes, during worker migration:

```text
Task worker -> ClaimNextTaskUseCase -> durable-to-typed mapper -> ExecuteTaskUseCase
            -> CompleteTaskProcessingUseCase / FailTaskProcessingUseCase
```

The current event path remains:

```text
Materialization worker -> engine-task-materialization -> current materialization storage
```

It becomes:

```text
Event worker -> claim EventConsumption -> CreateTasksForEventUseCase -> complete consumption
```

The legacy task-execution package can be deleted only after the task worker uses the typed route.
The materialization package can be deleted only after event consumption is independently stored
per `(pipelineId, pipelineVersion, eventId)`. Projection task orchestration can be deleted after
both target workers are active; `ComputePotBalancesUseCase` remains functional.

## Result of step 2

The domain modules now have explicit ownership: Pot and its events, Pot authorization policies,
the Balance projection calculation, pipeline identity, typed task payloads, and generic durable
consumption. `engine-core` contains only shared application contracts plus explicitly isolated
legacy types.

Functional engines own business Commands, Queries, typed Event-to-Task planning, typed Task
execution, and Balance projection. Their ports are consumer-oriented: queries use
`PotBalancesQueryPort`; Balance calculation uses `PotBalanceProjectionPort` and
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
