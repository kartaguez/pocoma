# Worker contract matrix

This matrix closes step 3. Tests are deterministic in-memory contract proofs; PostgreSQL
atomicity, rollback and structural uniqueness belong to step 4.

| Guarantee | Command | Event | Task |
|---|---|---|---|
| One item per iteration | `CommandWorkerIterationTest` | `EventWorkerIterationTest` | `TaskWorkerIterationTest` |
| Sequential per instance | `CommandWorkerTest.concurrentManualCallsAreSerialized` | `EventWorkerTest.concurrentManualCallsAreSerialized` | `TaskWorkerTest.concurrentManualCallsAreSerialized` |
| Parallel across instances | `CommandWorkerTest.distinctWorkerInstancesCanExecuteInParallel` | `EventWorkerTest.distinctWorkerInstancesCanCreateTasksInParallel` | `TaskWorkerTest.distinctWorkerInstancesCanExecuteInParallel` |
| Post-commit recovery | guard returns `ALREADY_EXECUTED` | creation returns `ALREADY_CREATED` | guard returns `ALREADY_EXECUTED` |
| Stale ownership | lifecycle returns `CLAIM_OWNERSHIP_LOST` | lifecycle returns `CLAIM_OWNERSHIP_LOST` | lifecycle returns `CLAIM_OWNERSHIP_LOST` |
| Lease warning/exceeded | `CommandWorkerIterationTest` | `EventWorkerIterationTest` | `TaskWorkerIterationTest` |
| Functional routing | `ExecuteCommandUseCase` | `CreateTasksForEventUseCase` | mapper registry then `ExecuteTaskUseCase` |
| Durable reconciliation | processing Command tests | no source mutation; processing Event tests | processing Task tests |
| Consumption cardinality | one key per command | key per pipeline/version/event | one key per task |

## Crash boundaries

```text
Command: claim → guard → business effect + journal → complete
Event:   claim → idempotent task creation         → complete
Task:    claim → guard → handler effect + journal → complete
```

For all three paths, tests cover no work, deterministic failure, uncertain technical failure,
completion loss after the protected effect, reclaim through the idempotence mechanism, and stale
ownership without compensation. A processing duration beyond the lease is observed but never
interrupted; V1 intentionally has no heartbeat.

## PostgreSQL proofs deferred to step 4

- lazy slot creation under concurrent transactions;
- CAS on `ConsumptionSlot.revision`;
- partial unique index for structurally active claims;
- invalidation of an expired claim before replacement;
- real transaction joining an execution journal and its effect;
- JDBC rollback and stale-token fencing.
