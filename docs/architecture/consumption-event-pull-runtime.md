# Event pull consumption runtime

The Event family is the first production path built exclusively on the target consumption lifecycle.

## Boundaries

`EventConsumptionLocator` discovers one Event/pipeline pair at a time and supplies a structural key, an
atomic callback and an exception classifier. A search owns only its cursor. PostgreSQL read transactions
end before acquisition and no Event outbox status is used as consumption authority.

`DefaultConsumptionOrchestrator` owns no transaction. It scans best-effort candidates and calls the short
transactional Acquire wrapper. Once acquired, it closes the search and invokes the transactional Execute
wrapper. Tasks, provenance and the final `current_claim_id` CAS therefore commit or rollback together.

`SupraConsumptionWorker` is a sequential runtime shell. It applies polling, known-eligibility and runtime
failure delays around complete orchestration cycles. A stop request does not interrupt an acquired
execution.

## Outcomes and failures

- SUCCESS, including a zero-Task transformation, commits Tasks, provenance and DONE/SUCCESS together.
- A future business REJECTED result follows the same atomic path and commits DONE/REJECTED.
- A technical exception first rolls back Execute, then is classified and passed to the independent short
  HandleFailure transaction.
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
