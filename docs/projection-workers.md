# Projection Workers

This document describes the projection pipeline used to compute pot balances under load.

## Overview

Commands still mutate the versioned business model synchronously. Projection work is now produced through durable database queues:

```text
HTTP command
  -> command transaction persists business state
  -> business_event_outbox row
  -> task-builder for the pot partition wakes by signal or timeout, then polls outbox
  -> projection_tasks row
  -> task-executor for the pot partition wakes by task signal, capacity signal, or timeout, then polls tasks
  -> SegmentedProjectionTaskExecutor asks ExecuteProjectionTasksUseCase to compute balances
  -> pot_balance_* projection tables
```

The database is the source of truth for pending work. In-memory queues are intentionally short-lived buffers inside a worker process. This keeps the API responsive during bursts: pressure accumulates as rows in `business_event_outbox` and `projection_tasks` instead of blocking request threads.

The system is at-least-once. Duplicate execution is tolerated because `ComputePotBalancesUseCase` is idempotent for an already projected version and persists with optimistic projection-state progression.

## Tables And States

`business_event_outbox` stores business events emitted by commands. Important fields:

- `event_type`, `pot_id`, `aggregate_id`, `version`, `payload_json`: the business event envelope.
- `pot_partition_hash`: stable hash computed from `pot_id`, used by workers to claim only their inter-process segment.
- `status`: `PENDING`, `CLAIMED`, `ACCEPTED`, `RUNNING`, `PROCESSED`, or `FAILED`.
- `claim_token`: fencing token generated for each claim.
- `claimed_by`: diagnostic worker id.
- `lease_until`: time after which another worker may reclaim the row.
- `trace_id`, `command_committed_at_nanos`: observability metadata propagated to projection metrics.

`projection_tasks` stores projection work. Important fields:

- `task_type`: currently `COMPUTE_BALANCES_FOR_VERSION`.
- `pot_id`, `target_version`: the requested projection target.
- `pot_partition_hash`: same stable hash as the outbox, so task executors claim only their segment.
- `status`: `PENDING`, `CLAIMED`, `ACCEPTED`, `RUNNING`, `DONE`, `FAILED`, or `SUPERSEDED`.
- `claim_token`, `claimed_by`, `lease_until`: same fencing and lease pattern as the outbox.
- `accepted_at`, `started_at`, `done_at`, `failed_at`: execution timeline.

There is one active `COMPUTE_BALANCES_FOR_VERSION` task per pot. When new events arrive for the same pot, the task-builder coalesces them by raising `target_version` to the highest event version.

## Components

`JpaBusinessEventOutboxAdapter` implements both `EventPublisherPort` and `BusinessEventOutboxPort`.

- As `EventPublisherPort`, it appends a business event row from existing command use cases.
- As `BusinessEventOutboxPort`, it claims batches of rows for task-builders and applies `ACCEPTED`, `RUNNING`, `PROCESSED`, `FAILED`, or release transitions using `claim_token`.
- It records `claimed_by` for debugging, but every state transition is protected by `claim_token`.

`JpaProjectionTaskAdapter` implements `ProjectionTaskPort`.

- It creates or updates `COMPUTE_BALANCES_FOR_VERSION` tasks from outbox events.
- It claims pending tasks with `FOR UPDATE SKIP LOCKED`.
- It validates lifecycle transitions using `claim_token`.

`core.taskbuilder.ProjectionTaskBuilderWorker` polls `business_event_outbox`. This is the first loop: business events to projection tasks.

- It waits for `BUSINESS_EVENTS_AVAILABLE(potId)` for its partition or `task-builder-polling-interval`, whichever happens first.
- It claims a batch of pending or expired events.
- It passes its `ProjectionPartition(segmentIndex, segmentCount)` to the engine use case, so the JPA adapter filters by `pot_partition_hash % segmentCount = segmentIndex`.
- It marks each claimed event `ACCEPTED`, then `RUNNING`.
- It transforms every balance-impacting business event into `COMPUTE_BALANCES_FOR_VERSION`.
- It marks the outbox event `PROCESSED` after the task is created or coalesced.
- It publishes `PROJECTION_TASKS_AVAILABLE(potId)` after creating or coalescing at least one task.
- If the transformation fails, it marks the event `FAILED`; if ownership is lost before running, it releases the event to `PENDING`.
- It can run in multiple processes because claims are lease-based.

`core.taskexecutor.ProjectionTaskExecutorWorker` polls `projection_tasks`. This is the second loop: projection tasks to in-memory execution.

- It waits for `PROJECTION_TASKS_AVAILABLE(potId)`, `CAPACITY_AVAILABLE(potId)`, or `task-executor-polling-interval`, but only for its partition.
- It asks `SegmentedProjectionTaskExecutor` for current queue capacity.
- It claims up to that capacity in one batch.
- It passes its `ProjectionPartition(segmentIndex, segmentCount)` to the engine use case before claiming.
- It calls `trySubmit` for each task.
- If the local worker refuses a task because a segment queue filled up meanwhile, it releases the row back to `PENDING`.

`core.taskexecutor.SegmentedProjectionTaskExecutor` executes projections.

- It partitions tasks by `potId`, preserving order per pot.
- It owns bounded per-segment queues.
- `trySubmit` returns immediately with accepted/refused instead of blocking.
- It delegates the actual balance computation to `ExecuteProjectionTasksUseCase.executeProjectionTask(...)`.
- It reports `RUNNING`, `DONE`, or `FAILED` directly through `ExecuteProjectionTasksUseCase`.
- It publishes `CAPACITY_AVAILABLE` when a segment queue frees capacity, coalesced by `capacity-wakeup-min-interval`.

There are two distinct segment notions:

- Inter-process partition: `pocoma.projection.worker.segment-index` / `segment-count`. This decides which rows a worker process may claim from the database and which wake events wake it.
- In-process executor segments: `pocoma.projection.worker.thread-count`. This decides which local queue executes a claimed task while preserving order per `potId`.

A hot pot belongs to exactly one inter-process partition for a given `segment-count`, so it wakes and is claimed by only one logical worker segment.

## Wake Signals

Wake signals are best-effort hints, not work items. They carry only the signal type and `potId`, never event payloads, task ids, or versions. The database remains the only source of truth.

`core.wakeup.ProjectionWorkerWakeBus` provides the local wake contract used by both loops:

- `BUSINESS_EVENTS_AVAILABLE(potId)`: emitted by an adapter when new business events may exist for the pot.
- `PROJECTION_TASKS_AVAILABLE(potId)`: emitted by the task-builder after at least one task was created or merged for the pot.
- `CAPACITY_AVAILABLE(potId)`: emitted by the segmented executor when bounded queues free capacity for the pot.

`ProjectionWorkerWakeBus` subscriptions include a `ProjectionPartition`. The in-memory Spring implementation routes an event only when `PotPartitioner.belongsTo(potId, partition)` is true. A future NATS adapter should use the same envelope shape and the same partitioning rule.

The timeout properties are still required. If a Spring event, a future NATS event, or a capacity signal is lost, the next timeout wakes the loop and the worker polls the database again.

The current Spring adapter only publishes `BUSINESS_EVENTS_AVAILABLE`. A future NATS adapter should publish the same wake signal into `ProjectionWorkerWakeBus`; it must not call the engine or submit projection tasks directly.

## Claim Tokens And Leases

`claim_token` is the functional ownership mechanism. `claimed_by` is only informational.

Every worker claim writes a new random token. Later transitions include that token:

```sql
update projection_tasks
set status = 'DONE'
where id = ?
  and claim_token = ?
```

If a worker pauses or dies after its lease expires, another worker may reclaim the row with a new token. The old worker can no longer complete the task because its token is stale.

Leases are intentionally short and renewable through heartbeat support. Current projection execution marks state at start and completion; long-running projections can add periodic heartbeat calls without changing table semantics.

## Spring Configuration

All properties use the `pocoma.projection.worker` prefix.

- `enabled`: enables the projection worker configuration.
- `thread-count`: segment count for `SegmentedProjectionTaskExecutor`.
- `queue-capacity`: queue size per segment.
- `max-retries`, `initial-backoff`, `max-backoff`: retry behavior inside a segment.
- `task-builder-enabled`: starts/stops `ProjectionTaskBuilderWorker`.
- `task-executor-enabled`: starts/stops `ProjectionTaskExecutorWorker`.
- `worker-id`: diagnostic id used in `claimed_by`.
- `segment-index`: inter-process segment owned by this worker.
- `segment-count`: total number of inter-process segments.
- `task-builder-batch-size`, `task-executor-batch-size`: polling batch limits.
- `task-builder-polling-interval`, `task-executor-polling-interval`: maximum wait time before polling again when no signal arrives.
- `task-builder-lease-duration`, `task-executor-lease-duration`: claim lease durations.
- `wake-signals-enabled`: enables best-effort wake signals; when disabled, workers still run by timeout.
- `capacity-wakeup-min-interval`: minimum delay between capacity wake signals emitted by the segmented executor.
- `event-listener-enabled`: Spring wake listener, disabled by default; it publishes only `BUSINESS_EVENTS_AVAILABLE`.

Typical local monolith uses both `task-builder-enabled=true` and `task-executor-enabled=true`. Production-style split mode uses `runtime-web-api` for HTTP commands/queries and `runtime-worker` for projection loops.

Command use cases publish business events through an outbox-first publisher. It writes the event to
`business_event_outbox`, then publishes the same business event through Spring only after transaction commit. In a
monolith with `event-listener-enabled=true`, the Spring listener converts this local event into a best-effort
`BUSINESS_EVENTS_AVAILABLE(potId)` wake signal. In split runtimes, Spring events remain process-local; a future NATS
adapter will publish the same wake signal contract between `runtime-web-api` and `runtime-worker`.

Examples:

```bash
cd app
./mvnw -pl runtime-web-api spring-boot:run -Dspring-boot.run.profiles=postgres

./mvnw -pl runtime-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.projection.worker.segment-index=0 --pocoma.projection.worker.segment-count=2"

./mvnw -pl runtime-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.projection.worker.segment-index=1 --pocoma.projection.worker.segment-count=2"
```

`runtime-monolith` remains useful for local development. Its `api` and `worker` profiles are still available, but the target split architecture should prefer the dedicated runtimes above.

## Metrics And Load Testing

Prometheus exposes:

- `pocoma_projection_outbox_pending`: business events pending or claimed.
- `pocoma_projection_tasks_pending`: projection tasks pending or in progress.
- existing projection latency, retry, and version-gap metrics.

The k6 scenario `scripts/k6/projection_backpressure.js` creates command bursts on hot and distributed pots and scrapes these metrics from the API runtime. A healthy run shows command latency staying bounded while backlog rises during the burst and drains afterward. With multiple workers, each worker should claim only its `pot_partition_hash` segment, and hot pots should not wake unrelated worker segments.

Example:

```bash
cd app
BASE_URL=http://localhost:8080 \
SEED_POTS=8 \
HOT_POTS=2 \
BACKPRESSURE_PEAK_RATE=60 \
BACKPRESSURE_PLATEAU=1m \
k6 run ../scripts/k6/projection_backpressure.js
```

If `pocoma_observed_projection_tasks_pending` never drains after load stops, increase worker capacity, add worker segments, or inspect `FAILED` tasks in the database.
