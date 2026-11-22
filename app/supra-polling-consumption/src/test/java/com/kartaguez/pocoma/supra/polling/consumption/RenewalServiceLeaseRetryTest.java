package com.kartaguez.pocoma.supra.polling.consumption;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports;
import com.kartaguez.pocoma.contract.consumption.invocation.ProtocolSafety;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Deterministic verification of {@link RenewalService} lease retry invariants, including
 * cross-definition batching where a sibling binding leads the retry batch.
 */
final class RenewalServiceLeaseRetryTest {

    private static final class CrossDefStubProtocol implements ConsumptionProtocol {
        final ConsumptionDefinition def;
        final AtomicBoolean omitFirstForTarget;
        final ConsumptionId targetId;
        final AtomicInteger batchCalls = new AtomicInteger();
        final List<List<ConsumptionId>> seenBatchIds = new CopyOnWriteArrayList<>();

        CrossDefStubProtocol(
                ConsumptionDefinition def,
                AtomicBoolean omitFirstForTarget,
                ConsumptionId targetId) {
            this.def = def;
            this.omitFirstForTarget = omitFirstForTarget;
            this.targetId = targetId;
        }

        @Override
        public boolean supportsCrossDefinitionRenewal() {
            return true;
        }

        @Override
        public Optional<Partition> acquirePartition(int partition) {
            return Optional.of(
                    new Partition() {
                        @Override
                        public ConsumptionId consumptionId() {
                            return def.id();
                        }

                        @Override
                        public int partitionId() {
                            return partition;
                        }

                        @Override
                        public boolean sameIncarnation(Partition other) {
                            return this == other;
                        }
                    });
        }

        @Override
        public List<Partition> renewPartitions(List<Partition> partitions) {
            return partitions;
        }

        @Override
        public List<Partition> renewPartitionBatch(
                List<ConsumptionDefinition> definitions, List<Partition> partitions) {
            batchCalls.incrementAndGet();
            seenBatchIds.add(partitions.stream().map(Partition::consumptionId).toList());
            if (omitFirstForTarget.compareAndSet(true, false)) {
                // Simulate PostgreSQL SKIP LOCKED omitting the target partition lease.
                return partitions.stream()
                        .filter(p -> !p.consumptionId().equals(targetId))
                        .toList();
            }
            return partitions;
        }

        @Override
        public void renewClaim(Handle handle) {}

        @Override
        public Navigation navigate(Partition p, ConsumptionNavigation.Walk w, int limit) {
            return new Navigation(List.of(), ConsumptionNavigation.Walk.fresh(), true);
        }

        @Override
        public Acquisition acquire(Partition p, List<UUID> keys) {
            return new Acquisition(List.of(), 0, Optional.of(ConsumptionNavigation.Walk.fresh()), false);
        }

        @Override
        public Acquisition acquire(Partition p, List<UUID> keys, ConsumptionNavigation.Walk c) {
            return new Acquisition(List.of(), 0, Optional.of(c), false);
        }

        @Override
        public Attempt execute(Handle handle) {
            return new Attempt(Status.COMMITTED, AttemptTransactions.Resolution.COMMITTED, null, null);
        }

        @Override
        public void abandon(Handle handle) {}

        @Override
        public boolean retainsCapacity(Handle handle) {
            return false;
        }

        @Override
        public int settlePending() {
            return 0;
        }

        @Override
        public boolean hasUnresolvedRecovery() {
            return false;
        }

        @Override
        public DiscoveryReports.Progress discover(Partition partition) {
            return new DiscoveryReports.Progress(
                    DiscoveryReports.Status.COMMITTED, OptionalLong.of(0L), 0, 0, 0);
        }
    }

    /** Mirrors ConsumptionSafety.retryOrExpireLease without depending on orchestrator-consumption. */
    private static ProtocolSafety retrySafety() {
        return new ProtocolSafety() {
            @Override
            public boolean renewalsQuiescent() {
                return true;
            }

            @Override
            public PhysicalOutcome opportunityOutcome(Throwable f) {
                return new PhysicalOutcome(
                        f == null
                                ? AttemptTransactions.Resolution.COMMITTED
                                : AttemptTransactions.Resolution.ROLLED_BACK,
                        f == null,
                        false);
            }

            @Override
            public PhysicalOutcome attemptOutcome(ConsumptionProtocol.Attempt a) {
                return new PhysicalOutcome(a.resolution(), true, false);
            }

            @Override
            public Optional<RenewalPermit> beginRenewal(
                    ConsumptionDefinition.Authority a, int lane, ConsumptionProtocol.Handle c) {
                return Optional.of(new RenewalPermit() {});
            }

            @Override
            public PhysicalOutcome finishRenewal(RenewalPermit p, Throwable f) {
                return new PhysicalOutcome(
                        AttemptTransactions.Resolution.COMMITTED, f == null, false);
            }

            @Override
            public LeaseDecision settleLease(
                    ConsumptionProtocol.Partition current,
                    List<ConsumptionProtocol.Partition> returned,
                    PhysicalOutcome outcome,
                    boolean running,
                    boolean usable,
                    long retryUntil,
                    long retryDue,
                    long due,
                    long callBegan,
                    long renewedAt,
                    Duration renewalBudget,
                    Duration renewalInterval,
                    Duration minimumPoll) {
                var renewed = returned.stream().filter(l -> l.sameIncarnation(current)).findFirst();
                if (renewed.isPresent()) {
                    return new LeaseDecision(false, renewed.get(), true, 0, 0, renewedAt, false);
                }
                long returnedAt = System.nanoTime();
                long effectiveRetryDue = retryUntil == 0 ? due : retryDue;
                long effectiveRetryUntil =
                        retryUntil == 0 ? returnedAt + renewalBudget.toNanos() : retryUntil;
                long nextDue = callBegan - renewalInterval.toNanos() + minimumPoll.toNanos();
                return new LeaseDecision(
                        false, current, false, effectiveRetryUntil, effectiveRetryDue, nextDue, usable);
            }
        };
    }

    private static void markRunning(ConsumptionWorker w, List<ConsumptionWorker.Binding> bindings)
            throws Exception {
        var f = ConsumptionWorker.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(w, com.kartaguez.pocoma.contract.consumption.invocation.WorkerView.State.RUNNING);
        var sc = ConsumptionWorker.class.getDeclaredField("startCalled");
        sc.setAccessible(true);
        sc.setBoolean(w, true);
        var init = ConsumptionWorker.class.getDeclaredField("initialized");
        init.setAccessible(true);
        init.setBoolean(w, true);
        for (var b : bindings) {
            for (int p : b.partitions()) assertNotNull(w.lease(b, p));
        }
    }

    @Test
    void omittedLeaseIsRetriedAndRetainsOriginalDueEvenWhenSiblingLeadsCrossDefinitionBatch()
            throws Exception {
        var defA =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var defB =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var omitFirst = new AtomicBoolean(true);
        var protoA = new CrossDefStubProtocol(defA, omitFirst, defA.id());
        var protoB = new CrossDefStubProtocol(defB, omitFirst, defA.id());
        var bindingA = new ConsumptionWorker.Binding(defA, protoA, List.of(0));
        var bindingB = new ConsumptionWorker.Binding(defB, protoB, List.of(0));

        var renewalDurations = new CopyOnWriteArrayList<Duration>();
        ConsumptionObservation observer =
                (id, phase, outcome, amount, elapsed) -> {
                    if (phase == ConsumptionObservation.Phase.RENEWAL_LEASE) {
                        renewalDurations.add(elapsed);
                    }
                };

        CapacityView cap =
                new CapacityView() {
                    @Override
                    public int workerLimit() {
                        return 2;
                    }

                    @Override
                    public boolean supports(ConsumptionId id) {
                        return true;
                    }

                    @Override
                    public Snapshot snapshot() {
                        return new Snapshot(0, Map.of());
                    }
                };
        var settings =
                new ConsumptionWorker.Settings(
                        32,
                        1,
                        Duration.ofMillis(2),
                        Duration.ofMillis(20),
                        Duration.ofMillis(50),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20));

        var worker =
                new ConsumptionWorker(
                        List.of(bindingA, bindingB),
                        cap,
                        Map.of(defA.id(), 2, defB.id(), 2),
                        settings,
                        observer,
                        () -> {},
                        (protocols, capacity) -> retrySafety());
        markRunning(worker, List.of(bindingA, bindingB));

        var pairA = new ConsumptionWorker.Pair(bindingA, 0);
        var pairB = new ConsumptionWorker.Pair(bindingB, 0);
        var entryA = worker.leases.get(pairA);
        var entryB = worker.leases.get(pairB);
        assertNotNull(entryA);
        assertNotNull(entryB);

        var renewalService = new RenewalService(worker);

        // Make only A due for pass 1 (B is not due yet), so A leads pass 1 and is omitted.
        long now = System.nanoTime();
        entryA.renewedAt = now - Duration.ofMillis(1100).toNanos();
        entryB.renewedAt = now - Duration.ofMillis(500).toNanos();

        assertTrue(renewalService.renewLeasesOnce(ConsumptionDefinition.Authority.READ));
        assertEquals(1, protoA.batchCalls.get());
        assertEquals(0, protoB.batchCalls.get());
        assertFalse(entryA.usable, "Omitted lease must be suspended pending retry");
        assertTrue(entryA.retryUntil != 0, "Omitted lease must enter bounded retry window");
        long originalRetryDue = entryA.retryDue;

        // Now make sibling B older than A's retry timestamp so B leads the cross-definition batch!
        entryB.renewedAt = entryA.renewedAt - Duration.ofMillis(50).toNanos();
        Thread.sleep(5); // advance past minPoll (2ms)

        assertTrue(renewalService.renewLeasesOnce(ConsumptionDefinition.Authority.READ));
        // Sibling B executed the cross-definition batch containing BOTH B and omitted A!
        assertEquals(1, protoA.batchCalls.get(), "A was not the batch leader on retry");
        assertEquals(1, protoB.batchCalls.get(), "Sibling B led the cross-definition retry batch");
        assertTrue(
                protoB.seenBatchIds.getFirst().contains(defA.id()),
                "Retried lease A must be included in sibling B's cross-definition renewal batch");
        assertTrue(entryA.usable, "Lease A must be restored to usable after successful retry");
        assertEquals(0L, entryA.retryUntil, "Retry window must clear after successful retry");
        assertEquals(2, renewalDurations.size());
        assertTrue(
                renewalDurations.get(1).compareTo(renewalDurations.get(0)) >= 0,
                "Retry call must retain original due ("
                        + originalRetryDue
                        + "), durations="
                        + renewalDurations);
    }
}
