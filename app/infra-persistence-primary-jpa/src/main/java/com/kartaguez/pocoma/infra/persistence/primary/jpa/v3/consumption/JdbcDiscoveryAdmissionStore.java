package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/**
 * Shared SQL for either effects authority. No source writes, lifecycle mutation or hidden owner.
 */
public final class JdbcDiscoveryAdmissionStore implements DiscoveryAdmissionStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final DiscoveryEngine.Settings settings;

    public JdbcDiscoveryAdmissionStore(DataSource source, DiscoveryEngine.Settings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    private long checkpoint(ConsumptionId id, int partition) {
        Objects.requireNonNull(id);
        if (partition < 0 || partition >= 4)
            throw new IllegalArgumentException("invalid partition");
        var rows =
                jdbc.queryForList(
                        "select position from discovery_checkpoint where consumption_id=? and"
                                + " partition_id=?",
                        Long.class,
                        id.value(),
                        partition);
        long k = rows.isEmpty() ? 0 : rows.getFirst();
        if (k < 0) throw new IllegalStateException("negative checkpoint");
        return k;
    }

    public long checkpoint(ConsumptionId id, int partition, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        jdbc.queryForObject(
                "select set_config('lock_timeout',?,true)",
                String.class,
                settings.lockTimeout().toMillis() + "ms");
        jdbc.queryForObject(
                "select set_config('statement_timeout',?,true)",
                String.class,
                settings.statementTimeout().toMillis() + "ms");
        return checkpoint(id, partition);
    }

    public long currentCheckpoint(PartitionLease lease) {
        return SpringAttemptTransactions.guarded(
                source, () -> checkpoint(lease.consumptionId(), lease.partitionId()));
    }

    public long liveSlots(PartitionLease lease) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        jdbc.queryForObject(
                                "select count(*) from consumption_slot where consumption_id=? and"
                                        + " partition_id=? and state<>'QUARANTINED'",
                                Long.class,
                                lease.consumptionId().value(),
                                lease.partitionId()));
    }

    private int hash(ConsumptionDefinition d, int partition, UUID id) {
        int hash = RoutingV1.hash(d.family(), id);
        if (partition != RoutingV1.partition(hash, d.partitionCount()))
            throw new IllegalArgumentException("wrong routed partition");
        return hash;
    }

    public boolean contains(ConsumptionDefinition d, int partition, UUID id) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    int hash = hash(d, partition, id);
                    var rows =
                            jdbc.queryForList(
                                    "select partition_hash,partition_id from consumption_slot where"
                                            + " consumption_id=? and consumable_id=?",
                                    d.id().value(),
                                    id);
                    if (rows.isEmpty()) return false;
                    if (!Integer.valueOf(hash).equals(rows.getFirst().get("partition_hash"))
                            || !Integer.valueOf(partition)
                                    .equals(rows.getFirst().get("partition_id")))
                        throw new IllegalStateException("slot routing identity mismatch");
                    return true;
                });
    }

    public void insertInitial(ConsumptionDefinition d, int partition, UUID id) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    int hash = hash(d, partition, id);
                    int count =
                            jdbc.update(
                                    "insert into"
                                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                        + " values (?,?,?,?)",
                                    d.id().value(),
                                    id,
                                    hash,
                                    partition);
                    if (count != 1) throw new IllegalStateException("initial admission rowcount");
                    return null;
                });
    }

    public void advanceCheckpoint(PartitionLease lease, long expected, long next) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    if (expected < 0 || next <= expected)
                        throw new IllegalArgumentException("checkpoint must advance");
                    int count;
                    var present =
                            jdbc.queryForList(
                                    "select position from discovery_checkpoint where"
                                            + " consumption_id=? and partition_id=?",
                                    Long.class,
                                    lease.consumptionId().value(),
                                    lease.partitionId());
                    if (present.isEmpty()) {
                        if (expected != 0)
                            throw new IllegalStateException("missing expected checkpoint");
                        count =
                                jdbc.update(
                                        "insert into"
                                            + " discovery_checkpoint(consumption_id,partition_id,position)"
                                            + " values (?,?,?)",
                                        lease.consumptionId().value(),
                                        lease.partitionId(),
                                        next);
                    } else
                        count =
                                jdbc.update(
                                        "update discovery_checkpoint set position=? where"
                                            + " consumption_id=? and partition_id=? and position=?",
                                        next,
                                        lease.consumptionId().value(),
                                        lease.partitionId(),
                                        expected);
                    if (count != 1) throw new IllegalStateException("checkpoint CAS failed");
                    return null;
                });
    }
}
