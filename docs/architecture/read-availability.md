# READ materialization availability

WRITE records authoritative facts. Seven independent READ materializers turn those facts into queryable projections. Availability records when an effect actually becomes durable in READ. This gives a downstream reader a way to discover completed materialization without polling the WRITE source and guessing whether the projection has caught up.

## Implemented families

Historical families retain an immutable event per logical effect. Current families retain a marker for the latest committed advance of each key.

| Kind | Family | Logical key | Publication |
| --- | --- | --- | --- |
| Historical | `POT_VERSION_AVAILABLE` | Pot ID and Pot version | Each new manifest, including an older version arriving late and the terminal deleted version. |
| Historical | `POT_BALANCE_AVAILABLE` | Pot ID and Pot version | Each new balance pointer for an active version, including reuse of existing balance content and versions without an aggregate. |
| Historical | `COMMAND_RESULT_AVAILABLE` | Command request ID | Each terminal result, accepted or rejected. |
| Historical | `IDENTITY_REQUEST_RESULT_AVAILABLE` | Identity request ID | Each terminal result, accepted or rejected. |
| Current | `CURRENT_POT_CHANGED` | Pot ID | Each strict advance of the current manifest pointer, including deletion. |
| Current | `POT_VISIBILITY_CHANGED` | Pot ID | Each newer visibility evaluation, including an evaluation with the same visible set. |
| Current | `CURRENT_BINDING_CHANGED` | External user identity ID (`eui_id`) | Each strict advance of the binding version, including the inactive state after `Unlink`. |
| Current | `CURRENT_INVITATION_CHANGED` | Invitation ID | Each strict advance of the invitation version. |

Each family has a separate transactional counter. A position describes commit progress within one family and one READ incarnation. A logical version belongs to the projection and its key. The materializers keep their own slots and checkpoints. For example, observing a PotVersion event gives no timing guarantee for PotBalance or visibility. When the PotVersion materializer also advances CurrentPot, it publishes both in the same commit and allocates the PotVersion position first. A late manifest can therefore become available without moving the current pointer backwards.

At `DeletePot`, the terminal manifest and deleted current pointer remain discoverable. Visibility advances to the terminal evaluation with an empty visible set. PotBalance intentionally has no terminal balance, aggregate or availability event. Existing active balance history remains available.

## Publication and recovery

A materializer writes its READ effect and records whether it inserted a historical row or advanced a current projection. The existing slot completion protocol validates that effect and its expected availability, then deletes the slot under its lease and claim fencing. The JDBC adapter enters a guarded callback, closes business publication via `closePublication()`, and allocates positions and writes only availability metadata in the same transaction. Deferred PostgreSQL constraints check that allocations, metadata and projection effects match at commit. A failure in either half of a double publication rolls back both, including slot completion and counter advances.

A strict current advance holds the projection row lock through completion. The validation can therefore compare the stored version with its previous marker without racing a later advance. Visibility uses a transient version-zero row to acquire the per-Pot lock. The row must advance and receive a marker before commit.

A repeated insert or a stale or equal current version does not allocate a new position. Recovery after an uncertain commit examines the effect, availability, slot and checkpoint in the existing READ observation. A retained historical event proves the original effect. A newer current marker can still prove recovery when it matches the observed projection and the stored effect satisfies the participant's source checks.

## Reconstruction

The exclusive READ rebuild recreates the schema and replays retained WRITE facts through the same materializers. It checks checkpoints, slots, projection integrity and the correspondence between effects and availability before returning to READY. A completed rebuild recovers the logical historical keys and versions and converges to the current projections. UUIDs and positions are regenerated, and replay order can change current positions. A non-destructive resume keeps the incarnation and preserves UUIDs and positions already committed.

The final readiness check scans the availability stock and the projected effect keys in both directions. The maintenance flow defers this check until checkpoint and slot checks pass. HTTP readiness reads the maintenance authority.

Availability is operational inside PostgreSQL. Downstream analytical projections and SSE delivery remain future work. The current delivery boundary and pagination protocol are described in the [READ availability reference](../reference/read-availability.md). For activation on an existing database, see [READ reconstruction and availability](../operations/recovery.md#read-reconstruction-and-availability).
