# READ availability reference

Availability is an internal PostgreSQL contract in `v3_read`. The [public HTTP API](api.md) exposes projection queries and result polling. The SQL page function and historical journal are used for internal qualification. A downstream reader needs explicitly scoped access and its own cursor storage. See the [family catalogue](../architecture/read-availability.md#implemented-families) for business keys and publication conditions.

## Records and identity

| Relation | Fields used for discovery | Identity and constraints |
| --- | --- | --- |
| `read_availability_counter` | `family`, `position` | One counter per family, starting at zero. The database tracks the allocating transaction internally. |
| `read_availability_event` | `family`, `position`, `event_id`, `projection_key`, `logical_version` | Immutable row keyed by family and position. The event UUID is distinct from the business key and unique in the journal. There is also one row per family, business key and logical version, including when the version is null. |
| `read_current_availability` | `family`, `projection_key`, `logical_version`, `position` | One row per family and business key. Positions are unique within a family. Version and position advance strictly on replacement. |

All business keys are UUIDs. The Pot historical families include a positive logical version. Both terminal result families have a null logical version because request ID identifies the effect. Current markers always have a positive logical version. The columns carry metadata only. The projected value lives in its READ relation. [Canonical READ V5 SQL](../../app/infra-postgres-read/src/main/resources/db/read/availability/migration/V5__projection_availability.sql)

## Historical pages

Maintain a cursor per family, initially zero. Read rows for that family with `position > cursor` ordered by ascending position and limited to the page size. Each committed historical position from 1 through the family head corresponds to exactly one row. If the reader needs the head with the page, read both in the same PostgreSQL statement snapshot or repeatable-read transaction. After processing the page, persist the last processed position. An empty page leaves the cursor at the same position.

## Current pages

The internal function `read_current_availability_page(family, cursor_position, page_size)` returns one row containing `head`, `next_cursor` and a JSON array of `changes`. Each item has `projection_key`, `logical_version` and `position` in ascending position order. The head and page use a single statement snapshot. The function requires a current family, a nonnegative cursor and a positive page size. A cursor beyond the head is rejected with SQLSTATE `22023`.

- A full page advances to its last marker position.
- A short or empty page advances to the head returned by that statement.
- Persist the returned cursor only after processing the complete page. On interruption before persisting, repeat from the last saved cursor and handle repeated observations idempotently.

A replacement removes the previous position from the register and assigns a fresh one. Positions are therefore sparse. A replacement committed after the snapshot receives a position beyond that snapshot's head and can appear on a later page, even when its former marker was already acknowledged. Intermediate versions may coalesce, and a separate read of the projection can see a newer version than the marker. Eventual discovery covers a stable key when transactions finish and the reader keeps progressing.

## Concurrency, privileges and retention

Allocations serialize on each family's counter row until commit or rollback. A rollback leaves the counter unchanged, and an allocation cannot become visible without its metadata in the same commit. Families have independent position orders. PostgreSQL permissions give the materializer the metadata operations needed for publication and readiness. The page function is available to the schema owner for qualification. The HTTP READ role has no C3 metadata grants.

The JDBC suffix sends one SQL statement per due family. A writable CTE increments the counter and passes the allocated position to the metadata write. The counter lock lasts until the surrounding transaction resolves. The current register indexes both the business key and position for lookup and paging. Replacing the indexed position creates new index entries and requires vacuum cleanup.

Within a READ incarnation, historical events accumulate and current registers retain one marker per key, including terminal or inactive states. There is no time-based retention or purge protocol. A restart or non-destructive rebuild resume preserves the cursor space. A full READ rebuild resets it. Coordinate resynchronization of all readers before using the rebuilt data, since an old cursor can fall within the new head and still refer to the previous incarnation. See [reconstruction](../architecture/read-availability.md#reconstruction).
