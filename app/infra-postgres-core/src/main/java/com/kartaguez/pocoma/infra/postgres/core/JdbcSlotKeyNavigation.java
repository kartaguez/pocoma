package com.kartaguez.pocoma.infra.postgres.core;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation.*;

import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.SlotKeyNavigation;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Bounded raw keys excluding QUARANTINED slots, no lease/retry eligibility predicate or row lock. U and first page share RR snapshot. */
public final class JdbcSlotKeyNavigation implements SlotKeyNavigation {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final LifecycleSettings settings;

    public JdbcSlotKeyNavigation(DataSource source, LifecycleSettings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    private static final String BASE_SELECT =
            "select consumable_id from consumption_slot where consumption_id=? and"
                    + " partition_id=? and state <> 'QUARANTINED'";

    private record PageScope(
            ConsumptionId consumption, int partition, UUID upper, int limit) {}

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
        validateBounds(partition, limit);
        applySessionTimeouts();
        var upper = resolveUpperBound(consumption, partition, walk);
        if (upper.isEmpty()) return new Page(upper, List.of());
        var scope = new PageScope(consumption, partition, upper.get(), limit);
        return new Page(upper, fetchKeys(scope, walk));
    }

    private void validateBounds(int partition, int limit) {
        if (partition < 0 || partition >= 4) {
            throw new IllegalArgumentException("invalid bounded slot page");
        }
        if (limit < 1 || limit > settings.batchSize()) {
            throw new IllegalArgumentException("invalid bounded slot page");
        }
    }

    private void applySessionTimeouts() {
        jdbc.queryForList(
                "select set_config('lock_timeout',?,true),"
                        + " set_config('statement_timeout',?,true),"
                        + " set_config('transaction_timeout',?,true)",
                settings.lockTimeout().toMillis() + "ms",
                settings.statementTimeout().toMillis() + "ms",
                (settings.statementTimeout().toMillis() + 1000) + "ms");
    }

    private Optional<UUID> resolveUpperBound(
            ConsumptionId consumption, int partition, Walk walk) {
        if (walk.upperInclusive().isPresent()) return walk.upperInclusive();
        return jdbc.query(
                        BASE_SELECT + " order by consumable_id desc limit 1",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        consumption.value(),
                        partition)
                .stream()
                .findFirst();
    }

    private List<UUID> fetchKeys(PageScope scope, Walk walk) {
        if (walk.afterExclusive().isEmpty()) {
            return jdbc.query(
                    BASE_SELECT + " and consumable_id<=? order by consumable_id limit ?",
                    (rs, n) -> rs.getObject(1, UUID.class),
                    scope.consumption().value(),
                    scope.partition(),
                    scope.upper(),
                    scope.limit());
        }
        return jdbc.query(
                BASE_SELECT
                        + " and consumable_id>? and consumable_id<=? order by"
                        + " consumable_id limit ?",
                (rs, n) -> rs.getObject(1, UUID.class),
                scope.consumption().value(),
                scope.partition(),
                walk.afterExclusive().get(),
                scope.upper(),
                scope.limit());
    }
}
