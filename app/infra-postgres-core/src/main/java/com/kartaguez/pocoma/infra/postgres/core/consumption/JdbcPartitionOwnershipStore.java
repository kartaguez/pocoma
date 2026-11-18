package com.kartaguez.pocoma.infra.postgres.core.consumption;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

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
                    jdbc.queryForList(
                            "select set_config('lock_timeout', ?, true),"
                                    + " set_config('statement_timeout', ?, true),"
                                    + " set_config('transaction_timeout', ?, true)",
                            lockTimeout.toMillis() + "ms",
                            statementTimeout.toMillis() + "ms",
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
        return renewBatch(List.of(expected), leases, duration);
    }

    @Override
    public List<PartitionLease> renewBatch(
            List<ConsumptionDefinition> definitions,
            List<PartitionLease> leases,
            Duration duration) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    long us = LifecycleSettings.micros(duration, false);
                    if (leases.isEmpty()) return List.of();
                    var byId = new LinkedHashMap<com.kartaguez.pocoma.domain.consumption.ConsumptionId, ConsumptionDefinition>();
                    for (var def : definitions) byId.put(def.id(), def);
                    var sorted =
                            leases.stream()
                                    .sorted(
                                            Comparator.comparing(
                                                            (PartitionLease l) ->
                                                                    l.consumptionId().value())
                                                    .thenComparingInt(PartitionLease::partitionId))
                                    .toList();
                    UUID[] consumptionIds = new UUID[sorted.size()];
                    Integer[] partitionIds = new Integer[sorted.size()];
                    UUID[] ownerTokens = new UUID[sorted.size()];
                    for (int i = 0; i < sorted.size(); i++) {
                        var old = sorted.get(i);
                        if (!byId.containsKey(old.consumptionId()))
                            throw new IllegalArgumentException("foreign lease");
                        consumptionIds[i] = old.consumptionId().value();
                        partitionIds[i] = old.partitionId();
                        ownerTokens[i] = old.ownerToken();
                    }
                    var rows =
                            jdbc.queryForList(
                                    "with target as ("
                                        + " select c_id, p_id, tok"
                                        + " from unnest(?::uuid[], ?::int4[], ?::uuid[]) as t(c_id, p_id, tok)"
                                        + " order by c_id, p_id"
                                        + "), locked as ("
                                        + " select po.consumption_id, po.partition_id, t.tok"
                                        + " from partition_ownership po"
                                        + " join target t on po.consumption_id = t.c_id"
                                        + " and po.partition_id = t.p_id"
                                        + " order by po.consumption_id, po.partition_id"
                                        + " for update of po skip locked"
                                        + "), now_ts as ("
                                        + " select clock_timestamp() as ts"
                                        + ")"
                                        + " update partition_ownership po"
                                        + " set owned_until = greatest(po.owned_until,"
                                        + " n.ts + ? * interval '1 microsecond')"
                                        + " from locked l, now_ts n"
                                        + " where po.consumption_id = l.consumption_id"
                                        + " and po.partition_id = l.partition_id"
                                        + " and po.owner_token = l.tok"
                                        + " and po.owned_until > n.ts"
                                        + " returning po.consumption_id, po.partition_id,"
                                        + " po.owner_token, po.owned_until",
                                    new org.springframework.jdbc.support.SqlArrayValue("uuid", consumptionIds),
                                    new org.springframework.jdbc.support.SqlArrayValue("int4", partitionIds),
                                    new org.springframework.jdbc.support.SqlArrayValue("uuid", ownerTokens),
                                    us);
                    var result = new ArrayList<PartitionLease>(rows.size());
                    for (var r : rows) {
                        var cId = new com.kartaguez.pocoma.domain.consumption.ConsumptionId((UUID) r.get("consumption_id"));
                        var def = byId.get(cId);
                        int partition = ((Number) r.get("partition_id")).intValue();
                        result.add(lease(def, partition, r));
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
