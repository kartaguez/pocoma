# LatestKnownVersion cutover

This runbook renames the Java/runtime concept without changing its persisted protocol identity or SQL
storage. The normal cutover never fabricates terminal consumption slots.

## Compatibility invariants

- Consumer identity remains `SOURCE_VERSION_WATERMARK`.
- Tables remain `consumption_*` and `pocoma_read.source_version_watermarks`.
- The state column remains `latest_version_seen`.
- Existing values are the initial latest-known state.
- Existing terminal, pending and expired-claim slots retain their lifecycle meaning.

The persisted consumer name is now an opaque compatibility token, not current business vocabulary.
A future token or SQL rename would be a separate forward-only protocol/data migration.

## Nominal procedure

1. Deploy the renamed runtime disabled if the deployment system cannot stop/start it atomically.
2. Run [`latest-known-version-preflight.sql`](sql/latest-known-version-preflight.sql) read-only.
3. Diagnose any state below a version attested by a successful terminal slot. Do not continue blindly.
4. Stop the old runtime cleanly.
5. Wait until active claims are released or their leases expire.
6. Start `runtime-latest-known-version-consumption-worker` with the same database and persisted consumer
   identity.
7. Verify that new Events and pending/expired work converge, generic consumption lag falls, and
   `advanced|unchanged|error` outcomes are plausible.
8. Verify that no Task, artifact, projection failure or head is produced by this consumer.

Events without a slot remain eligible and pass through the normal lifecycle. An old Event already
subsumed by the initial max may therefore commit an `unchanged` outcome and honest input provenance.
No administrative adoption or bulk `DONE/SUCCESS` insert is part of this procedure.

## Exceptional repair

Repair is a separately approved data operation after a documented diagnosis, never an automatic
cutover step. Prefer rebuilding `LatestKnownVersion` from retained Events or successfully attested
inputs. Do not manufacture slot history merely to reduce backlog.

Changing slots is a last resort reserved for a precise slot-lifecycle anomaly. Such a repair needs its
own reviewed SQL, affected-row estimate, backup/recovery plan and post-validation; this runbook
intentionally contains no mutating slot statement.

## Post-cutover checks

- no active process uses the retired Java/runtime name;
- the database still contains the compatibility token and physical legacy names;
- pending and retry counts converge without a replay spike caused by a new consumer identity;
- latest-known never regresses, including under duplicate and out-of-order Events;
- projection lag and latest-known lag are monitored independently.
