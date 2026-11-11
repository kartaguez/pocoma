package com.kartaguez.pocoma.orchestrator.consumption.v3;

import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import java.util.*;

/** One facade over the existing L2 owner; it never reimplements a transaction or recovery. */
public abstract class AbstractConsumptionProtocol implements ConsumptionProtocol {
    final ConsumptionEngine engine;
    protected final DiscoveryEngine discovery;
    protected final ConsumptionDefinition definition;

    protected AbstractConsumptionProtocol(ConsumptionDefinition definition,
            ConsumptionEngine engine, DiscoveryEngine discovery) {
        this.definition = Objects.requireNonNull(definition);
        this.engine = Objects.requireNonNull(engine);
        this.discovery = Objects.requireNonNull(discovery);
        if (!definition.equals(engine.definition()) || !definition.equals(discovery.definition()))
            throw new IllegalArgumentException("Protocol providers belong to different definitions");
    }

    private final class OwnedPartition implements Partition {
        private final AbstractConsumptionProtocol issuer = AbstractConsumptionProtocol.this;
        private final PartitionLease lease;
        private OwnedPartition(PartitionLease lease) { this.lease = lease; }
        public ConsumptionId consumptionId() { return lease.consumptionId(); }
        public int partitionId() { return lease.partitionId(); }
        public boolean sameIncarnation(Partition other) {
            return other instanceof AbstractConsumptionProtocol.OwnedPartition p
                    && p.issuer == issuer
                    && lease.consumptionId().equals(p.lease.consumptionId())
                    && lease.partitionId() == p.lease.partitionId()
                    && lease.ownerToken().equals(p.lease.ownerToken());
        }
        // Each returned grant is a distinct observation, even if a fixture's DB clock is fixed.
        // An old issued view cannot suspend a newer observation of the same incarnation.
        @Override public String toString() { return "Partition[" + partitionId() + "]"; }
    }

    private final class OwnedClaim implements Handle {
        private final AbstractConsumptionProtocol issuer = AbstractConsumptionProtocol.this;
        private final ConsumptionEngine.AcquiredClaim claim;
        private OwnedClaim(ConsumptionEngine.AcquiredClaim claim) { this.claim = claim; }
        public ConsumptionId consumptionId() { return claim.claim().consumptionId(); }
        public UUID consumableId() { return claim.claim().consumableId(); }
        public int partitionId() { return RoutingV1.partition(RoutingV1.hash(definition.family(), consumableId()), definition.partitionCount()); }
    }

    final boolean owns(Handle handle) {
        return handle instanceof AbstractConsumptionProtocol.OwnedClaim claim && claim.issuer == this;
    }
    final boolean owns(Partition handle) {
        return handle instanceof AbstractConsumptionProtocol.OwnedPartition partition && partition.issuer == this;
    }

    protected final PartitionLease requirePartition(Partition handle) {
        if (!(handle instanceof AbstractConsumptionProtocol.OwnedPartition p) || p.issuer != this)
            throw new IllegalArgumentException("Foreign or fabricated partition handle");
        return p.lease;
    }

    private ConsumptionEngine.AcquiredClaim requireClaim(Handle handle) {
        if (!(handle instanceof AbstractConsumptionProtocol.OwnedClaim c) || c.issuer != this)
            throw new IllegalArgumentException("Foreign or fabricated claim handle");
        return c.claim;
    }

    public Optional<Partition> acquirePartition(int partition) {
        return engine.acquirePartition(partition).map(OwnedPartition::new);
    }
    public List<Partition> renewPartitions(List<Partition> partitions) {
        var leases = partitions.stream().map(this::requirePartition).toList();
        return engine.renewPartitions(leases).stream().map(l -> (Partition)new OwnedPartition(l)).toList();
    }
    public void renewClaim(Handle handle) { engine.renewClaims(List.of(requireClaim(handle).claim())); }
    public ConsumptionNavigation.Page navigate(Partition partition, ConsumptionNavigation.Walk walk, int limit) {
        return engine.navigate(requirePartition(partition), walk, limit);
    }
    public ConsumptionProtocol.Acquisition acquire(Partition partition, List<UUID> keys) {
        var acquired = engine.acquire(requirePartition(partition), keys);
        return new ConsumptionProtocol.Acquisition(acquired.claims().stream()
                .map(c -> (Handle)new OwnedClaim(c)).toList(), acquired.examinedCount());
    }
    public Attempt execute(Handle handle) { return engine.execute(requireClaim(handle)); }
    public void abandon(Handle handle) { engine.abandon(requireClaim(handle)); }
    public boolean retainsCapacity(Handle handle) { return engine.retainsCapacity(requireClaim(handle)); }
    public int settlePending() { return engine.settlePending(); }
    public boolean hasUnresolvedRecovery() { return engine.hasUnresolvedRecovery(); }
}
