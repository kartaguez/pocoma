# Event pull consumption runtime

The Event family is the first production path built exclusively on the target consumption lifecycle.

## Boundaries

`EventConsumptionLocator` discovers one Event/pipeline pair at a time and supplies a structural key, an
atomic callback and a technical-failure classifier. Discovery is best-effort: the callback captures the
Event id, not the `RecordedEvent` snapshot. Once Execute has opened its transaction, the callback reloads
the Event by id and derives Tasks and provenance exclusively from that authoritative read.

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

## Outcomes and failures

- SUCCESS, including a zero-Task transformation, commits Tasks, provenance and DONE/SUCCESS together.
- A deterministic task-planning rejection becomes `BusinessConsumptionOutcome.Rejected`; it commits the
  authoritative Event input and DONE/REJECTED without Task result, ProcessingFailure or retry.
- A technical exception first rolls back Execute, then is classified and passed to the independent short
  HandleFailure transaction.
- Event execution failures follow the 1 s, 5 s and 30 s retry schedule. Missing configuration or a missing
  authoritative Event is an invariant failure and terminates immediately as FAILED.
- `LostClaimException` is never classified and never reaches HandleFailure; it only denotes a stale,
  already rolled-back execution.

Direct HTTP, email or second-database effects are forbidden inside the callback. They must be represented
by a durable Task/outbox row.

## Migration

The old Event worker and its TryAcquire/ClaimToken/Complete/Fail/Release services are removed. Existing
materializations are adopted lazily by reading their durable Task references and recording them as results
of the newly created slot. Do not run the legacy materializer and the new Event runtime simultaneously for
the same pipeline during rollout.

Command and Task still use their legacy workers and may still depend on `engine-execution-guard`; their
migration and the final deletion of that guard belong to a later lot.

## Packages

- `orchestrator.consumption` contains the sequential algorithm; its `locator` and `model` subpackages
  contain discovery contracts and cycle values.
- `locator.consumption.event` composes Event reload and Task creation; its `failure` subpackage owns Event
  technical categories, classification and policy.
- `supra.consumption` contains the polling worker; interruptible waiting lives in `supra.consumption.wait`.
- `runtime.event.consumption` is the Spring composition root. No internal layer depends on it.
