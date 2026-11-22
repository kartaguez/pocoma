# Adding a READ projection

Start with the effect a reader needs to discover. Use a historical family when each immutable row matters independently. Use a current family when the reader needs the latest state per key and can accept coalesced updates. Each family has one meaning, one logical key shape and one cursor order. A new independent effect requires an explicit family and schema change. Share a family only when its existing contract describes the same effect and identity. See the [implemented families](../architecture/read-availability.md#implemented-families).

## Attach publication to the effect

1. Choose a stable business identifier from the authoritative WRITE fact. Add a logical version when multiple historical results can exist for one key. For a current projection, keep a stable key and choose the monotonic version used to arbitrate concurrent advances. Positions track discovery and are allocated by PostgreSQL.
2. Have the projection store return the outcome of its write. For a historical effect, distinguish a new insert from exact replay. For a current effect, distinguish a strict advance from an equal or superseded version and verify the provenance of the stored state. A terminal state can still be a current advance.
3. Keep the receipt with the prepared attempt. Use it in `requireCompletionReady` to verify the effect and publication expectation. Let `SlotCompletion.complete()` perform the protected slot deletion, then call the store's `closePublication` with that receipt. The adapter's private suffix closes business publication before allocating positions and writing metadata. Keep all three actions on the same guarded READ connection and keep counter order explicit when two families publish together.
4. Extend recovery to check metadata in the same observation as the projection. A historical event must match the exact logical key. A current marker must match the projection seen in that observation, which may have superseded the attempted version.

The [PotVersion participant](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersion.java) and [JDBC store](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotVersionStore.java) illustrate the historical insert and optional current advance in a single attempt. The [shared JDBC suffix](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/ReadAvailability.java) owns allocation and metadata writes.

## Qualify the schema and replay

- Declare the key and version shape, counter, constraints, indexes, privilege requirements and effect mapping for a new family. Cover both directions of the effect and availability invariant in the schema and rebuild oracle. Review the catalog check and the PostgreSQL page function if adding a current family.
- Test idempotent replay, conflicting values, stale and concurrent arrivals, slot fencing, rollback after a partial suffix, and recovery after a lost commit acknowledgement. For a current family, test page boundaries and replacement after a reader snapshot. Use an independent PostgreSQL observation to check committed effects, slots, counters and metadata.
- Use the canonical availability migration in integration fixtures exercising a real materializer. Keep intentionally partial HTTP or storage fixtures on their compatible schema. A fixture that writes an effect in an availability-enabled schema must publish its metadata in the same transaction.
- Wire the projection into the WRITE replay and readiness checks. Compare logical historical identities and current effects with an oracle derived independently from WRITE. Verify a full rebuild and an interrupted replay resumed from committed progress.
- Declare PROJECTION and any additional affected consumers or architecture gate before changing code. Use the canonical slice and PostgreSQL tests selected by the [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md).

The [L7-C3 decisions](../v3/L7_C3/arbitrages.md) record the transaction, discovery and activation choices. The [generalization report](../v3/L7_C3/generalization-report.md) links the original qualification evidence.
