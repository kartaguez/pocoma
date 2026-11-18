package com.kartaguez.pocoma.infra.postgres.source;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Closed mapping of the four delivered publication sources. No fallback to V2. */
public final class JdbcEventDiscoveryReferences implements NonBurnableReferences {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final DiscoverySettings settings;
    private final ConsumableFamily family;
    private final String sequence, table;
    private final Set<String> subjects;

    public JdbcEventDiscoveryReferences(
            DataSource source, ConsumableFamily family, DiscoverySettings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
        this.family = Objects.requireNonNull(family);
        switch (family) {
            case INVITATION_EVENT -> {
                sequence = "INVITATION";
                table = "v3_invitation_event";
                subjects = Set.of("INVITATION");
            }
            case POT_EVENT -> {
                sequence = "POT";
                table = "v3_pot_event";
                subjects = Set.of("POT");
            }
            case COMMAND_COMPLETION_EVENT -> {
                sequence = "COMMAND_COMPLETION";
                table = "v3_command_completion_event";
                subjects = Set.of("COMMAND");
            }
            case IDENTITY_EVENT -> {
                sequence = "IDENTITY";
                table = "v3_identity_event";
                subjects = Set.of("POCOMA_USER", "EXTERNAL_USER_IDENTITY");
            }
            case IDENTITY_COMPLETION_EVENT -> {
                sequence = "IDENTITY_COMPLETION";
                table = "v3_identity_completion_event";
                subjects = Set.of("REGISTRATION", "LINK", "UNLINK");
            }
            default ->
                    throw new IllegalArgumentException(
                            "V3 production source not delivered: " + family);
        }
    }

    public ConsumableFamily family() {
        return family;
    }

    @Override
    public Optional<Reference> find(UUID eventId, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
                    Objects.requireNonNull(eventId);
                    applyTimeouts();
                    var rows =
                            jdbc.query(
                                    "select"
                                        + " p.family,p.position,p.event_id,e.event_id,e.subject_type,c.position"
                                        + " from (values (cast(? as uuid))) q(event_id) left join"
                                        + " v3_event_position p on p.event_id=q.event_id left join "
                                            + table
                                            + " e on e.event_id=q.event_id"
                                            + " left join v3_event_counter c on c.family=?",
                                    (r, n) -> mapReferenceRow(r, eventId),
                                    eventId,
                                    sequence);
                    if (rows.size() != 1) throw new InvalidReference("Non-unique event reference");
                    return rows.getFirst();
                });
    }

    private Optional<Reference> mapReferenceRow(java.sql.ResultSet r, UUID eventId)
            throws java.sql.SQLException {
        Long published = r.getObject(6, Long.class);
        if (published == null || published < 0) {
            throw new InvalidReference("Missing or invalid publication counter");
        }
        UUID positioned = r.getObject(3, UUID.class);
        UUID envelope = r.getObject(4, UUID.class);
        if (positioned == null && envelope == null) return Optional.empty();
        Long position = r.getObject(2, Long.class);
        String subject = r.getString(5);
        if (!isValidReference(r.getString(1), positioned, envelope, eventId)
                || !isValidPositionAndSubject(position, published, subject)) {
            throw new InvalidReference("Incompatible exact publication reference");
        }
        return Optional.of(new Reference(position, eventId, subject));
    }

    private boolean isValidReference(
            String rowSequence, UUID positioned, UUID envelope, UUID eventId) {
        if (!eventId.equals(positioned) || !eventId.equals(envelope)) return false;
        return sequence.equals(rowSequence);
    }

    private boolean isValidPositionAndSubject(Long position, Long published, String subject) {
        if (position == null) return false;
        if (position < 1 || position > published) return false;
        return subject != null && subjects.contains(subject);
    }

    private void applyTimeouts() {
        jdbc.queryForList(
                "select set_config('lock_timeout',?,true), set_config('statement_timeout',?,true)",
                settings.lockTimeout().toMillis() + "ms",
                settings.statementTimeout().toMillis() + "ms");
    }

    public Window window(long after, int max, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
                    if (after < 0 || max < 1) throw new IllegalArgumentException("invalid window bound");
                    if (max > settings.windowSize()) {
                        throw new IllegalArgumentException("invalid window bound");
                    }
                    applyTimeouts();
                    var counters =
                            jdbc.queryForList(
                                    "select position from v3_event_counter where family=?",
                                    Long.class,
                                    sequence);
                    if (counters.size() != 1 || counters.getFirst() < after) {
                        throw new IllegalStateException("missing or regressed publication counter");
                    }
                    long high = after + Math.min((long) max, counters.getFirst() - after);
                    if (high == after) return new Window(after, high, List.of());
                    var refs = queryWindowRefs(after, high);
                    verifyContiguousWindow(after, high, refs);
                    return new Window(after, high, refs);
                });
    }

    private List<Reference> queryWindowRefs(long after, long high) {
        // LEFT JOIN retains missing envelopes so they cause a fault instead of disappearing.
        return jdbc.query(
                "select p.position,p.event_id,e.subject_type from v3_event_position p left join "
                        + table
                        + " e on e.event_id=p.event_id where p.family=? and p.position>? and"
                        + " p.position<=? order by p.position",
                (r, n) -> {
                    if (!subjects.contains(r.getString(3))) {
                        throw new IllegalStateException("missing or incompatible event envelope");
                    }
                    return new Reference(
                            r.getLong(1), r.getObject(2, UUID.class), r.getString(3));
                },
                sequence,
                after,
                high);
    }

    private static void verifyContiguousWindow(long after, long high, List<Reference> refs) {
        if (refs.size() != high - after) {
            throw new IllegalStateException("hole in published prefix");
        }
        long position = after;
        var ids = new HashSet<UUID>();
        for (var ref : refs) {
            if (ref.position() != ++position || !ids.add(ref.eventId())) {
                throw new IllegalStateException("non-contiguous publication or duplicate identity");
            }
        }
    }
}
