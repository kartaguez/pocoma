# Event pull consumption runtime

The active Event runtime is the canonical EPT materializer described by
[`../steps/EPT/Step_Canon.md`](../steps/EPT/Step_Canon.md). It turns durable Event metadata into
canonical `ProjectionTask` rows; it does not calculate projections.

## Active graph

```text
ConsumptionPollingWorker
  → AcquireThenFinalizeConsumptionOrchestrator<ProjectionMaterializationCandidate>
    → ProjectionMaterializationConsumptionSource
      → JdbcProjectionMaterializationDiscoveryAdapter
    → TransactionalAcquireConsumptionUseCase
    → ProjectionMaterializationConsumptionService
      → TransactionalFinalizeConsumptionUseCase
        → JdbcProjectionTaskStoreAdapter.ensure(...)
```

The unit of work is exactly:

```text
EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]
```

The worker must be configured with a non-empty `pocoma.event-consumption.projection-types` set. The
EventTypes and concrete routes are derived from `PocomaProjectionMaterializationPolicy`; there is no
parallel EventType, pipeline or generation configuration. One worker may serve one or several
ProjectionTypes.

Discovery reads only the durable envelope metadata. It does not load `payload_json`, and it excludes
only the exact Event × ProjectionType slot already `DONE`. Its local cursor is reconstructible and is
not a durable watermark.

## Segmentation

`segment-index` and `segment-count` select rows by the normalized modulo of
`business_event_outbox.pot_partition_hash`. Every consequence of one Event therefore remains in the
same Pot segment. This deliberately replaces the former pipeline × Pot hash; no compatibility mapping
between the two segmentation functions exists.

## Transactions and failures

Acquisition creates the slot and Claim through the generic Consumption lifecycle. Finalization locks
and fences the current Claim before calling `ProjectionTaskStore.ensure`. Task creation, Claim
`SUCCESS` and Slot `DONE/SUCCESS` share one local transaction.

The runtime reuses the semantics delivered by EPT.4: technical failures rollback or surface as a
runtime failure and do not create an Event-specific terminal failure policy. Takeover, stale-Claim
fencing and replay remain responsibilities of the existing Consumption components.

## Legacy boundary

The former `EventConsumptionLocator` graph, pipeline registries,
`TransactionalExecuteConsumptionUseCase`, provenance and `CanonicalProjectionTaskScheduler` may remain
compiled for audit and for users outside this runtime, but they are not reachable from the active Event
worker. Historical `PROJECTION_TASK_SCHEDULER` slots and `tasks_4_pipeline` rows may remain stored; new
Event materialization does not write them.

The independently deployed LatestKnownVersion runtime remains a separate direct consumer under
`EVENT[eventId] / SOURCE_VERSION_WATERMARK[]` and is not part of this graph.
