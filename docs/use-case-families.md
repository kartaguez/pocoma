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
`PipelineTaskPayload` contains only the work to perform: no durable id, claim, lease, trace, status,
or JSON. Worker bindings affect only pull eligibility and never direct functional execution.

The current worker-facing `PipelineTask` route remains transitional. Its runtime strategy decodes
the persisted JSON into a typed payload and delegates to `ExecuteTaskUseCase`; completion, failure,
retry, polling, and worker lifecycle remain outside the typed engine.

## Durable consumption domain — `domain-consumption`

The durable processing domain owns `ClaimToken`, leases, statuses, failures, segmentation, and
their invariants. It contains no use case, persistence concern, or worker orchestration.

Static segmentation uses a stable `PartitionHash` and a configured `WorkerSegment`. Commands with
a Pot id use the Pot id as their partition key. Commands without a Pot id are unsegmented and will
later be made eligible to every Command worker segment; atomic claiming will select a single
owner. EventConsumptions and Tasks use `(pipelineId, potId)` as their partition key.

Claim ordering is also explicit and is independent from segmentation:

- Commands are ordered by `(createdAt, commandId)` only;
- EventConsumptions are ordered by `(appliesAtVersion, createdAt, eventId)`;
- Tasks are ordered by `(appliesAtVersion, createdAt, taskId)`.

The identifier is a deterministic tie-breaker. These rules guarantee claim priority among eligible
items, not completion order between concurrent workers.

## Durable consumption use cases — `engine-consumption`

Technical processing use cases own the claim/complete/fail/release operations for durable
Commands, EventConsumptions, and Tasks. They depend on `domain-consumption`, are reusable by
workers, and remain separate from functional use cases.

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
