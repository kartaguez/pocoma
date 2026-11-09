package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine.Settings;
import com.kartaguez.pocoma.engine.consumption.v3.port.BurnableReferences;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** IdentityRequest references only; no intention, claim or lifecycle selection. */
public final class JdbcIdentityRequestDiscoveryReferences implements BurnableReferences {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final Settings settings;

    public JdbcIdentityRequestDiscoveryReferences(DataSource source, Settings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    public ConsumableFamily family() {
        return ConsumableFamily.IDENTITY_REQUEST;
    }

    public Window window(long after, int max, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
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
                                    "select position from v3_request_counter where"
                                            + " family='IDENTITY_REQUEST'",
                                    Long.class);
                    if (counters.size() != 1 || counters.getFirst() < after)
                        throw new IllegalStateException("missing or regressed publication counter");
                    long high = after + Math.min((long) max, counters.getFirst() - after);
                    var refs =
                            jdbc.query(
                                    "select p.position,p.request_id,r.request_id from"
                                            + " v3_request_position p left join"
                                            + " v3_identity_request r on"
                                            + " r.request_id=p.request_id where"
                                            + " p.family='IDENTITY_REQUEST' and p.position>? and"
                                            + " p.position<=? order by p.position",
                                    (r, n) -> {
                                        if (r.getObject(3, UUID.class) == null)
                                            throw new IllegalStateException(
                                                    "missing identity request envelope");
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
                                        "select request_id from v3_identity_request where"
                                            + " request_id=? and status='PENDING' for update skip"
                                            + " locked",
                                        Objects.requireNonNull(id))
                                .isEmpty());
    }

    public boolean isTerminal(UUID id) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        !jdbc.queryForList(
                                        "select request_id from v3_identity_request where"
                                                + " request_id=? and status='TERMINAL'",
                                        Objects.requireNonNull(id))
                                .isEmpty());
    }
}
