# Event pull consumption runtime

The Event family is the first production path built exclusively on the target consumption lifecycle.

## Boundaries

`EventConsumptionLocator` discovers one Event/generation scheduling trigger at a time and supplies a structural key, an
atomic callback and a technical-failure classifier. Discovery is best-effort: the callback captures the
Event id, not the `RecordedEvent` snapshot. Once Execute has opened its transaction, the callback reloads
the Event by id, re-evaluates the complete canonical catalogue, and ensures Tasks and provenance exclusively
from that authoritative read. Event relevance is decided once per `pipelineId`; generation production is
then decided only by `PipelineVersionDefinition.appliesTo(event.version())`.

A `ConsumptionSearch` owns only its cursor, pagination state and local resources. It never retains a
transaction, lock or JPA session between `next()` and acquisition, and it is always closed before an
acquired execution starts. PostgreSQL discovery transactions therefore end before acquisition and no
legacy Event outbox status is used as consumption authority.

`SequentialConsumptionOrchestrator` owns no transaction. It scans best-effort candidates and calls the short
transactional Acquire wrapper. Once acquired, it closes the search and invokes the transactional Execute
wrapper. Tasks, provenance and the final `current_claim_id` CAS therefore commit or rollback together.

`ConsumptionPollingWorker` is a sequential runtime shell. It applies polling, known-eligibility and runtime
failure delays around complete orchestration cycles. A stop request does not interrupt an acquired
execution.

The independently deployable latest-known-version runtime reuses this generic lifecycle with the
compatibility key `EVENT[eventId] / SOURCE_VERSION_WATERMARK[]`. Its locator reloads the authoritative
Event and atomically advances the read-side state with `max(stored, event.version())`; it creates no
Task and does not reuse or impersonate an Event-to-Task pipeline identity. The max-upsert, Event input
provenance and fenced terminal CAS are one PostgreSQL transaction.

Direct read-side materialization is allowed only for a deterministic or naturally idempotent effect
that is bounded, short, local to the slot's transactional resource, has no external call and needs no
autonomous durable handoff. Otherwise Event consumption must hand off to a durable Task. Therefore
latest-known-version uses the direct mode, while versioned projections use Event -> Task -> Executor.

## Outcomes and failures

- SUCCESS, including a zero-Task transformation, commits Tasks, provenance and DONE/SUCCESS together.
- A deterministic task-planning rejection becomes `BusinessConsumptionOutcome.Rejected`; it commits the
  authoritative Event input and DONE/REJECTED with its rejection code as terminal reason, without Task
  result, ProcessingFailure or retry.
- A technical exception first rolls back Execute, then is classified and passed to the independent short
  HandleFailure transaction.
- Event execution failures follow the 1 s, 5 s and 30 s retry schedule. Missing configuration or a missing
  authoritative Event is an invariant failure and terminates immediately as FAILED, using the precise
  failure code as terminal reason. The separate category remains the policy input. Retryable failures
  leave the slot PENDING without terminal reason.
- `LostClaimException` is never classified and never reaches HandleFailure; it only denotes a stale,
  already rolled-back execution.

Direct HTTP, email, long-running or second-database effects are forbidden inside the callback. They
must be represented by a durable Task/outbox row.

## Migration

The scheduler key is
`EVENT[eventId] / PROJECTION_TASK_SCHEDULER[pipelineId,pipelineVersion]`. A terminal slot closes only that
exact generation: when the append-only catalogue gains another applicable generation, normal discovery
finds the old Event again through the missing direct Task identity. `tasks_4_pipeline` is uniquely identified
for this Event-derived subtype by `(event_id,pipeline_id,pipeline_version)`; V10 removes the legacy
materialization parent. This Event-derived identity deliberately remains extensible to future administrative
Tasks, whose provenance will not require an Event.

Command now uses `runtime-command-consumption-worker` and the same generic consumption lifecycle;
its former worker and `engine-execution-guard` have been removed. Task keeps its own current runtime
until its dedicated cleanup.

## Packages

- `orchestrator.consumption` contains the sequential algorithm; its `locator` and `model` subpackages
  contain discovery contracts and cycle values.
- `locator.consumption.event` composes Event reload and Task creation; its `failure` subpackage owns Event
  technical categories, classification and policy.
- `supra.consumption` contains the polling worker; interruptible waiting lives in `supra.consumption.wait`.
- `runtime.event.consumption` is the Spring composition root. No internal layer depends on it.
- `runtime.latestknownversion` is the independent Spring composition root for the direct transactional
  latest-known-version consumer. No Event-to-Task engine depends on it.
