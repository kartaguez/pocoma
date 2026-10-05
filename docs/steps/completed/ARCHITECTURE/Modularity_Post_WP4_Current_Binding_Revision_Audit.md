# POST-WP4.C — CURRENT_BINDING revision semantics

**Baseline audited:** `12c9c66440717c4bf7cb6e7735272ce9aa0cbbc0`. **Classification: C2 — monotonic maximum convergence.** The previous D.10 wording that materialization requires contiguous `R → R+1` described the authority's revision allocation, not the READ adapter's acceptance rule. This audit does not change the adapter algorithm.

## Protocol and guarantees

1. `JpaExternalIdentityBindingAdapter` creates the stream if absent, locks its `(issuer, subject)` row `FOR UPDATE`, reads `current_revision`, and performs acquisition/detach, stream advance to `R+1`, and immutable fact append inside the same mandatory transaction. A conflicting acquire returns before the initializer. Rollback removes all these effects together. The stream lock and unique constraints provide per-E uniqueness and contiguous **production** revisions; a later revision for the same E cannot commit ahead of an earlier transaction holding that row lock. Different E streams can commit independently.
2. `JdbcBindingFactDiscoveryAdapter` scans eligible facts in `(issuer, subject, revision)` order, segmented by the fact partition hash. Its cursor is local to one scan invocation, never persisted. This order is discovery order, not an execution serialization guarantee. A late commit below the cursor can be found by a reconstructed scan. `BindingFactConsumptionLocator` reloads the immutable fact by event ID after claim; the consumption key is that event ID, with a separate slot and lease per fact.
3. The materializer maps a fact to a complete ATTACHED `(E,U,B,R,event)` or DETACHED `(E,R,event)` snapshot. `JdbcCurrentBindingAdapter.apply` uses one READ transaction and a conditional upsert: `incoming R > stored R` applies; lower R is stale; equal R with identical status, U, B and source event ID is duplicate; equal R with divergent payload throws. The projected timestamp is excluded from payload identity. The row and finalization participate in the worker's transactional effect/claim lifecycle; retry after effect but before finalize is idempotent. A lost claim rolls back its effect, and competing workers can execute different revisions out of order. The higher committed revision wins.
4. Restart rebuilds the invocation-local scan. No persisted discovery cursor hides unconsumed facts. Revision-zero migration facts establish a bootstrap snapshot with a source event ID; subsequent positive revisions replace it. A transient failure is retried by the consumption lifecycle. A terminal invariant failure remains observable and requires repair; no asynchronous projection promises convergence in the presence of an unrepaired terminal failure or stopped worker.

## Scenario matrix

| Scenario | Actual result |
|---|---|
| Current R, incoming R+1 | Applied. |
| Current R, incoming R+2 | Applied; the intermediate state may never become visible. |
| R+2 before R+1 | R+2 applied; later R+1 stale. |
| R+1 arrives/retries after R+2 | Stale; cannot resurrect old U. |
| Duplicate R, identical payload/event | Duplicate, regardless of projected timestamp. |
| Same current R, divergent payload/event | Invariant exception. A lower revision is stale without payload comparison. |
| Crash after apply before finalize | Transactional rollback or idempotent replay, depending on commit point; no lower revision can overwrite a higher one. |
| Multiple workers | Per-fact claim and lease; out-of-order execution is resolved by maximum revision. Lost claim is fenced. |
| Restart/bootstrap | New scan discovers pending facts; revision-zero bootstrap is source-backed and subject to the same maximum rule. |
| Late commit | The scan cursor is ephemeral, so a later scan can discover a fact committed after an earlier scan passed its key. Same-E writer locking prevents a lower revision from committing after a higher one. |

The existing Postgres proofs in `CurrentBindingPersistencePostgresTest` cover jump, stale, duplicate, divergent same revision, detach/rebind, and bootstrap. `BindingRuntimePostgresTest` covers authority→fact→projection, late commit, reconstruction/retry, lost claim, and multiple workers. These are the relevant proofs for the C2 decision.

## Answer for TBD-E2U

The acceptance of `R → R+n` can skip intermediate projected states; it **cannot by itself produce an incorrect final state** when the latest authoritative fact for E is eventually discovered, successfully applied, and no later authoritative revision exists. Every fact is a complete snapshot, and lower revisions cannot overwrite the maximum. The `R+1` continuity of fact production does not need to be imposed on materialization for GET Pot. During lag, GET Pot can use stale ATTACHED U, including after a PRIMARY detach; this is the explicitly accepted convergent revocation contract. An absent or DETACHED READ row yields no projected U. The remaining operational precondition is eventual processing of the latest fact; an unrepaired terminal failure or stopped worker can prolong staleness without a code-defined upper bound.
