package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine.Settings;
import com.kartaguez.pocoma.engine.consumption.v3.port.NonBurnableReferences;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Closed mapping of the two actually delivered publication sources. No fallback to V2. */
public final class JdbcEventDiscoveryReferences implements NonBurnableReferences {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final Settings settings;
    private final ConsumableFamily family;
    private final String sequence, table, subject;

    public JdbcEventDiscoveryReferences(
            DataSource source, ConsumableFamily family, Settings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
        this.family = Objects.requireNonNull(family);
        switch (family) {
            case POT_EVENT -> {
                sequence = "POT";
                table = "v3_pot_event";
                subject = "POT";
            }
            case COMMAND_COMPLETION_EVENT -> {
                sequence = "COMMAND_COMPLETION";
                table = "v3_command_completion_event";
                subject = "COMMAND";
            }
            default ->
                    throw new IllegalArgumentException(
                            "V3 production source not delivered: " + family);
        }
    }

    public ConsumableFamily family() {
        return family;
    }

    public Window window(long after, int max, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        if (after < 0 || max < 1 || max > settings.windowSize())
            throw new IllegalArgumentException("invalid window bound");
        jdbc.queryForObject(
                "select set_config('lock_timeout',?,true)",
                String.class,
                settings.lockTimeout().toMillis() + "ms");
        jdbc.queryForObject(
                "select set_config('statement_timeout',?,true)",
                String.class,
                settings.statementTimeout().toMillis() + "ms");
        var counters =
                jdbc.queryForList(
                        "select position from v3_event_counter where family=?",
                        Long.class,
                        sequence);
        if (counters.size() != 1 || counters.getFirst() < after)
            throw new IllegalStateException("missing or regressed publication counter");
        long high = after + Math.min((long) max, counters.getFirst() - after);
        // LEFT JOIN retains missing envelopes so they cause a fault instead of disappearing.
        var refs =
                jdbc.query(
                        "select p.position,p.event_id,e.subject_type from v3_event_position p left"
                                + " join "
                                + table
                                + " e on e.event_id=p.event_id where p.family=? and p.position>?"
                                + " and p.position<=? order by p.position",
                        (r, n) -> {
                            if (!subject.equals(r.getString(3)))
                                throw new IllegalStateException(
                                        "missing or incompatible event envelope");
                            return new Reference(
                                    r.getLong(1), r.getObject(2, UUID.class), r.getString(3));
                        },
                        sequence,
                        after,
                        high);
        if (refs.size() != high - after)
            throw new IllegalStateException("hole in published prefix");
        long position = after;
        var ids = new HashSet<UUID>();
        for (var ref : refs)
            if (ref.position() != ++position || !ids.add(ref.eventId()))
                throw new IllegalStateException("non-contiguous publication or duplicate identity");
        return new Window(after, high, refs);
    }
}
