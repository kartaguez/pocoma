package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.LifecycleSettings;
import com.kartaguez.pocoma.engine.consumption.v3.port.SlotKeyNavigation;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Bounded raw keys, no eligibility predicate or row lock. U and first page share RR snapshot. */
public final class JdbcSlotKeyNavigation implements SlotKeyNavigation {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final LifecycleSettings settings;

    public JdbcSlotKeyNavigation(DataSource source, LifecycleSettings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    @Override
    public Page readPage(
            ConsumptionId consumption,
            int partition,
            Walk walk,
            int limit,
            RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        Objects.requireNonNull(consumption);
        Objects.requireNonNull(walk);
        if (partition < 0 || partition >= 4 || limit < 1 || limit > settings.batchSize())
            throw new IllegalArgumentException("invalid bounded slot page");
        jdbc.queryForObject(
                "select set_config('lock_timeout',?,true)",
                String.class,
                settings.lockTimeout().toMillis() + "ms");
        jdbc.queryForObject(
                "select set_config('statement_timeout',?,true)",
                String.class,
                settings.statementTimeout().toMillis() + "ms");
        jdbc.queryForObject(
                "select set_config('transaction_timeout',?,true)",
                String.class,
                (settings.statementTimeout().toMillis() + 1000) + "ms");
        String prefix =
                "select consumable_id from consumption_slot where consumption_id=? and"
                        + " partition_id=?";
        var upper = walk.upperInclusive();
        if (upper.isEmpty())
            upper =
                    jdbc
                            .query(
                                    prefix + " order by consumable_id desc limit 1",
                                    (rs, n) -> rs.getObject(1, UUID.class),
                                    consumption.value(),
                                    partition)
                            .stream()
                            .findFirst();
        if (upper.isEmpty()) return new Page(upper, List.of());
        List<UUID> keys;
        if (walk.afterExclusive().isEmpty())
            keys =
                    jdbc.query(
                            prefix + " and consumable_id<=? order by consumable_id limit ?",
                            (rs, n) -> rs.getObject(1, UUID.class),
                            consumption.value(),
                            partition,
                            upper.get(),
                            limit);
        else
            keys =
                    jdbc.query(
                            prefix
                                    + " and consumable_id>? and consumable_id<=? order by"
                                    + " consumable_id limit ?",
                            (rs, n) -> rs.getObject(1, UUID.class),
                            consumption.value(),
                            partition,
                            walk.afterExclusive().get(),
                            upper.get(),
                            limit);
        return new Page(upper, keys);
    }
}
