package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.PartitionOwnershipStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/**
 * Short Engine-owned transactions. Routing SELECT serializes with administrative ACCESS EXCLUSIVE.
 */
public final class JdbcPartitionOwnershipStore implements PartitionOwnershipStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcPartitionOwnershipStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
    }

    @Override
    public void bound(Duration lockTimeout, Duration statementTimeout) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    LifecycleSettings.micros(lockTimeout, false);
                    LifecycleSettings.micros(statementTimeout, false);
                    jdbc.queryForObject(
                            "select set_config('lock_timeout', ?, true)",
                            String.class,
                            lockTimeout.toMillis() + "ms");
                    jdbc.queryForObject(
                            "select set_config('statement_timeout', ?, true)",
                            String.class,
                            statementTimeout.toMillis() + "ms");
                    jdbc.queryForObject(
                            "select set_config('transaction_timeout', ?, true)",
                            String.class,
                            statementTimeout.plusSeconds(1).toMillis() + "ms");
                    return null;
                });
    }

    private void route(ConsumptionDefinition expected, int partition) {
        if (partition < 0 || partition >= expected.partitionCount())
            throw new IllegalArgumentException("invalid partition");
        var rows =
                jdbc.queryForList(
                        "select family,routing_version,partition_count from"
                                + " consumption_routing_contract where consumption_id=?",
                        expected.id().value());
        if (rows.size() != 1
                || !expected.family().name().equals(rows.getFirst().get("family"))
                || !expected.routingVersion().equals(rows.getFirst().get("routing_version"))
                || !Integer.valueOf(expected.partitionCount())
                        .equals(rows.getFirst().get("partition_count")))
            throw new IllegalStateException("missing or incompatible routing contract");
    }

    private List<Map<String, Object>> lock(ConsumptionDefinition expected, int partition) {
        route(expected, partition);
        return jdbc.queryForList(
                "select owner_token,owned_until from partition_ownership where consumption_id=? and"
                        + " partition_id=? for update skip locked",
                expected.id().value(),
                partition);
    }

    private Instant now() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private PartitionLease lease(
            ConsumptionDefinition expected, int partition, Map<String, Object> row) {
        return new PartitionLease(
                expected.id(),
                partition,
                (UUID) row.get("owner_token"),
                ((Timestamp) row.get("owned_until")).toInstant());
    }

    @Override
    public Optional<PartitionLease> acquire(
            ConsumptionDefinition expected, int partition, UUID token, Duration duration) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    Objects.requireNonNull(token);
                    long us = LifecycleSettings.micros(duration, false);
                    if (lock(expected, partition).isEmpty()) return Optional.empty();
                    var time = now();
                    var rows =
                            jdbc.queryForList(
                                    "update partition_ownership set"
                                        + " owner_token=?,owned_until=?::timestamptz + ? * interval"
                                        + " '1 microsecond' where consumption_id=? and"
                                        + " partition_id=? and (owner_token is null or"
                                        + " owned_until<=?) returning owner_token,owned_until",
                                    token,
                                    Timestamp.from(time),
                                    us,
                                    expected.id().value(),
                                    partition,
                                    Timestamp.from(time));
                    return rows.stream().findFirst().map(r -> lease(expected, partition, r));
                });
    }

    @Override
    public List<PartitionLease> renew(
            ConsumptionDefinition expected, List<PartitionLease> leases, Duration duration) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    long us = LifecycleSettings.micros(duration, false);
                    var result = new ArrayList<PartitionLease>();
                    for (var old :
                            leases.stream()
                                    .sorted(Comparator.comparingInt(PartitionLease::partitionId))
                                    .toList()) {
                        if (!old.consumptionId().equals(expected.id()))
                            throw new IllegalArgumentException("foreign lease");
                        if (lock(expected, old.partitionId()).isEmpty()) continue;
                        var time = now();
                        var rows =
                                jdbc.queryForList(
                                        "update partition_ownership set"
                                            + " owned_until=greatest(owned_until,?::timestamptz + ?"
                                            + " * interval '1 microsecond') where consumption_id=?"
                                            + " and partition_id=? and owner_token=? and"
                                            + " owned_until>? returning owner_token,owned_until",
                                        Timestamp.from(time),
                                        us,
                                        expected.id().value(),
                                        old.partitionId(),
                                        old.ownerToken(),
                                        Timestamp.from(time));
                        rows.forEach(r -> result.add(lease(expected, old.partitionId(), r)));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public Optional<PartitionLease> protectAcquisition(
            ConsumptionDefinition expected, PartitionLease requested) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    if (!requested.consumptionId().equals(expected.id()))
                        throw new IllegalArgumentException("foreign lease");
                    var rows = lock(expected, requested.partitionId());
                    var time = now(); // Only after actual locks; never use the caller's cached
                    // deadline.
                    return rows.stream()
                            .filter(
                                    r ->
                                            requested.ownerToken().equals(r.get("owner_token"))
                                                    && r.get("owned_until")
                                                            instanceof Timestamp until
                                                    && until.toInstant().isAfter(time))
                            .findFirst()
                            .map(r -> lease(expected, requested.partitionId(), r));
                });
    }
}
