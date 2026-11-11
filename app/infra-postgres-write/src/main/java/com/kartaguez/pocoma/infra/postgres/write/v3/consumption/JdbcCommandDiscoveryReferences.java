package com.kartaguez.pocoma.infra.postgres.write.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.spi.BurnableReferences;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Command references only; no intention, claim or lifecycle selection. */
public final class JdbcCommandDiscoveryReferences implements BurnableReferences {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final DiscoverySettings settings;

    public JdbcCommandDiscoveryReferences(DataSource source, DiscoverySettings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    public ConsumableFamily family() {
        return ConsumableFamily.COMMAND;
    }

    public Window window(long after, int max, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
                    if (after < 0 || max < 1 || max > settings.windowSize())
                        throw new IllegalArgumentException("invalid window bound");
                    jdbc.queryForList(
                            "select set_config('lock_timeout',?,true),"
                                    + " set_config('statement_timeout',?,true)",
                            settings.lockTimeout().toMillis() + "ms",
                            settings.statementTimeout().toMillis() + "ms");
                    var counters =
                            jdbc.queryForList(
                                    "select position from v3_request_counter where"
                                            + " family='COMMAND'",
                                    Long.class);
                    if (counters.size() != 1 || counters.getFirst() < after)
                        throw new IllegalStateException("missing or regressed publication counter");
                    long high = after + Math.min((long) max, counters.getFirst() - after);
                    if (high == after) return new Window(after, high, List.of());
                    var refs =
                            jdbc.query(
                                    "select p.position,p.request_id,c.request_id from"
                                            + " v3_request_position p left join v3_command c on"
                                            + " c.request_id=p.request_id where p.family='COMMAND'"
                                            + " and p.position>? and p.position<=? order by"
                                            + " p.position",
                                    (r, n) -> {
                                        if (r.getObject(3, UUID.class) == null)
                                            throw new IllegalStateException(
                                                    "missing command envelope");
                                        return new Reference(
                                                r.getLong(1), r.getObject(2, UUID.class));
                                    },
                                    after,
                                    high);
                    if (refs.size() != high - after)
                        throw new IllegalStateException("hole in published prefix");
                    long position = after;
                    var ids = new HashSet<UUID>();
                    for (var ref : refs)
                        if (ref.position() != ++position || !ids.add(ref.requestId()))
                            throw new IllegalStateException(
                                    "non-contiguous publication or duplicate identity");
                    return new Window(after, high, refs);
                });
    }

    public boolean tryProtectPending(UUID id) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        !jdbc.queryForList(
                                        "select request_id from v3_command where request_id=? and"
                                                + " status='PENDING' for update skip locked",
                                        Objects.requireNonNull(id))
                                .isEmpty());
    }

    public boolean isTerminal(UUID id) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        !jdbc.queryForList(
                                        "select request_id from v3_command where request_id=? and"
                                                + " status='TERMINAL'",
                                        Objects.requireNonNull(id))
                                .isEmpty());
    }
}
