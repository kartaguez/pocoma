package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** Position allocation and metadata writes on the projection's existing guarded READ transaction. */
final class ReadAvailability {
    enum Family {
        POT_VERSION_AVAILABLE,
        POT_BALANCE_AVAILABLE,
        COMMAND_RESULT_AVAILABLE,
        IDENTITY_REQUEST_RESULT_AVAILABLE,
        CURRENT_POT_CHANGED,
        POT_VISIBILITY_CHANGED,
        CURRENT_BINDING_CHANGED,
        CURRENT_INVITATION_CHANGED
    }

    private final ReadJdbc db;
    private final boolean enabled;

    ReadAvailability(ReadJdbc db, boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * Empty when the logical event is absent. A present value reports whether its position
     * belongs to the committed family prefix visible to the caller.
     */
    Optional<Boolean> historicalEventPositionValid(Family family, UUID key, Long version) {
        String sql = """
                select coalesce(event.position <= counter.position, false)
                from read_availability_event event
                left join read_availability_counter counter using (family)
                where event.family = ? and event.projection_key = ?
                """;
        var matches = version == null
                ? db.sql.queryForList(sql, Boolean.class, family.name(), key)
                : db.sql.queryForList(sql + " and event.logical_version = ?", Boolean.class,
                        family.name(), key, version);
        return matches.stream().findFirst();
    }

    void requireHistoricalEvent(Family family, UUID key, Long version, boolean expectExisting) {
        if (!enabled) return;
        var observed = historicalEventPositionValid(family, key, version);
        if (expectExisting ? !observed.orElse(false) : observed.isPresent()) {
            throw new IllegalStateException(
                    "Historical availability differs for " + family + ", key " + key + ", version " + version
                            + ": expected " + (expectExisting ? "a valid event" : "no event")
                            + ", observed " + observed.map(valid -> valid ? "a valid event" : "an invalid position")
                                    .orElse("no event"));
        }
    }

    boolean currentMarkerMatches(Family family, UUID key, Long actualVersion) {
        if (!enabled) return true;
        var observed = currentMarker(family, key);
        return actualVersion == null ? observed.isEmpty()
                : observed.filter(marker -> marker.positionValid() && marker.version() == actualVersion).isPresent();
    }

    <T> ReadEvidence currentEvidence(Family family, UUID key, long incomingVersion,
            T expected, Optional<T> actual, ToLongFunction<T> versionOf) {
        Long actualVersion = actual.map(versionOf::applyAsLong).orElse(null);
        if (!currentMarkerMatches(family, key, actualVersion)) {
            return ReadEvidence.DIVERGENT;
        }
        if (actualVersion == null || actualVersion < incomingVersion) {
            return ReadEvidence.MISSING;
        }
        if (actualVersion > versionOf.applyAsLong(expected)) {
            return ReadEvidence.UNAVAILABLE;
        }
        return actual.get().equals(expected) ? ReadEvidence.SATISFIED : ReadEvidence.DIVERGENT;
    }

    void requireCurrent(Family family, UUID key, long actualVersion, boolean advanced) {
        if (!enabled) return;
        var observed = currentMarker(family, key);
        boolean valid = advanced
                ? observed.isEmpty() || observed.filter(
                        marker -> marker.positionValid() && marker.version() < actualVersion).isPresent()
                : observed.filter(marker -> marker.positionValid() && marker.version() == actualVersion).isPresent();
        if (!valid) {
            throw new IllegalStateException(
                    "Current availability differs for " + family + ", key " + key
                            + ", stored version " + actualVersion + ", advanced " + advanced
                            + ", observed marker " + observed);
        }
    }

    private Optional<CurrentMarker> currentMarker(Family family, UUID key) {
        return db.sql.query(
                """
                select marker.logical_version, coalesce(marker.position <= counter.position, false)
                from read_current_availability marker
                left join read_availability_counter counter using (family)
                where marker.family = ? and marker.projection_key = ?
                """,
                (row, index) -> new CurrentMarker(row.getLong(1), row.getBoolean(2)), family.name(), key)
                .stream().findFirst();
    }

    private record CurrentMarker(long version, boolean positionValid) {}

    record Publication(Family family, UUID key, Long version, UUID eventId) {
        static Publication historical(Family family, UUID key, Long version, boolean inserted) {
            return inserted ? new Publication(family, key, version, UUID.randomUUID()) : null;
        }

        static Publication current(Family family, UUID key, long version, boolean advanced) {
            return advanced ? new Publication(family, key, version, null) : null;
        }
    }

    void closeAndPublish(Publication... publications) {
        if (!enabled || java.util.Arrays.stream(publications).allMatch(java.util.Objects::isNull)) {
            SpringAttemptTransactions.closePublication(db.source);
            return;
        }
        db.write(() -> {
            SpringAttemptTransactions.closePublication(db.source);
            // Enter the guarded callback while publication is open. After closing it,
            // only the private metadata writer below may use this transaction.
            for (var publication : publications) {
                if (publication != null) publishMetadata(publication);
            }
            return null;
        });
    }

    private void publishMetadata(Publication publication) {
        String family = publication.family().name();
        // Pass the allocated position through RETURNING. The volatile position trigger
        // checks the counter written by this transaction before accepting the metadata row.
        String allocate = """
                with allocated as (
                    update read_availability_counter set position = position + 1
                    where family = ? returning family, position
                )
                """;
        int affected;
        if (publication.eventId() == null) {
            affected = db.sql.update(allocate + """
                    insert into read_current_availability(family, projection_key, logical_version, position)
                    select family, ?, ?, position from allocated
                    on conflict (family, projection_key) do update
                    set logical_version = excluded.logical_version, position = excluded.position
                    """, family, publication.key(), publication.version());
        } else if (publication.version() == null) {
            // Result families are unversioned. The isolated pilot has no version column.
            affected = db.sql.update(allocate + """
                    insert into read_availability_event(family, position, event_id, projection_key)
                    select family, position, ?, ? from allocated
                    """, family, publication.eventId(), publication.key());
        } else {
            affected = db.sql.update(allocate + """
                    insert into read_availability_event(family, position, event_id, projection_key, logical_version)
                    select family, position, ?, ?, ? from allocated
                    """, family, publication.eventId(), publication.key(), publication.version());
        }
        if (affected != 1) {
            throw new IllegalStateException("Availability publication did not write exactly one row for " + family);
        }
    }
}
