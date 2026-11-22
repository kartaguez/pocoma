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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class L7C4PollingOptimizationTest {

    private static final class StubProtocol implements ConsumptionProtocol {
        final ConsumptionDefinition def;
        final AtomicInteger navigateCalls = new AtomicInteger();
        final AtomicInteger discoverCalls = new AtomicInteger();
        volatile int admissionsNext;
        volatile long checkpoint = 10L;

        StubProtocol(ConsumptionDefinition def) {
            this.def = def;
        }

        @Override
        public Optional<Partition> acquirePartition(int partition) {
            return Optional.of(new Partition() {
                public ConsumptionId consumptionId() { return def.id(); }
                public int partitionId() { return partition; }
                public boolean sameIncarnation(Partition other) { return this == other; }
            });
        }

        @Override public List<Partition> renewPartitions(List<Partition> partitions) { return partitions; }
        @Override public void renewClaim(Handle handle) {}

        @Override
        public Navigation navigate(Partition partition, ConsumptionNavigation.Walk walk, int limit) {
            navigateCalls.incrementAndGet();
            return new Navigation(List.of(), ConsumptionNavigation.Walk.fresh(), true);
        }

        @Override public Acquisition acquire(Partition p, List<UUID> keys, ConsumptionNavigation.Walk c) {
            return new Acquisition(List.of(), 0, Optional.of(c), false);
        }
        @Override public Acquisition acquire(Partition p, List<UUID> keys) {
            return new Acquisition(List.of(), 0, Optional.of(ConsumptionNavigation.Walk.fresh()), false);
        }
        @Override public Attempt execute(Handle handle) {
            return new Attempt(Status.COMMITTED, com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution.COMMITTED, null, null);
        }
        @Override public void abandon(Handle handle) {}
        @Override public boolean retainsCapacity(Handle handle) { return false; }
        @Override public int settlePending() { return 0; }
        @Override public boolean hasUnresolvedRecovery() { return false; }

        @Override
        public DiscoveryReports.Progress discover(Partition partition) {
            return discover(partition, true);
        }

        @Override
        public DiscoveryReports.Progress discover(Partition partition, boolean verifyOwnershipOnEmpty) {
            assertFalse(verifyOwnershipOnEmpty, "DiscoveryLoop must use fast-path verifyOwnershipOnEmpty=false");
            discoverCalls.incrementAndGet();
            int adm = admissionsNext;
            admissionsNext = 0;
            if (adm > 0) checkpoint += adm;
            return new DiscoveryReports.Progress(
                    DiscoveryReports.Status.COMMITTED, OptionalLong.of(checkpoint), adm, 0, adm);
        }
    }

    private static ConsumptionWorker worker(List<ConsumptionWorker.Binding> bindings, Duration settle) {
        var caps = new HashMap<ConsumptionId, Integer>();
        for (var b : bindings) caps.put(b.definition().id(), 2);
        CapacityView cap = new CapacityView() {
            @Override public int workerLimit() { return 2; }
            @Override public boolean supports(ConsumptionId id) { return true; }
            @Override public Snapshot snapshot() { return new Snapshot(0, Map.of()); }
        };
        var settings = new ConsumptionWorker.Settings(
                32, 1, Duration.ofMillis(2), Duration.ofMillis(20), Duration.ofMillis(50),
                settle, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(30));
        ProtocolSafety safety = new ProtocolSafety() {
            @Override public boolean renewalsQuiescent() { return true; }
            @Override public PhysicalOutcome opportunityOutcome(Throwable f) { return new PhysicalOutcome(f == null ? com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution.COMMITTED : com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution.ROLLED_BACK, f == null, false); }
            @Override public PhysicalOutcome attemptOutcome(ConsumptionProtocol.Attempt a) { return new PhysicalOutcome(a.resolution(), true, false); }
            @Override public Optional<RenewalPermit> beginRenewal(com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority a, int lane, ConsumptionProtocol.Handle c) { return Optional.of(new RenewalPermit(){}); }
            @Override public PhysicalOutcome finishRenewal(RenewalPermit p, Throwable f) { return new PhysicalOutcome(com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution.COMMITTED, true, false); }
            @Override public LeaseDecision settleLease(ConsumptionProtocol.Partition c, List<ConsumptionProtocol.Partition> r, PhysicalOutcome o, boolean running, boolean usable, long ru, long rd, long due, long cb, long ra, Duration rb, Duration ri, Duration mp) {
                return new LeaseDecision(false, c, usable, ru, rd, ra, false);
            }
        };
        return new ConsumptionWorker(
                bindings, cap, caps, settings, ConsumptionObservation.noop(), () -> {},
                (protocols, capacity) -> safety);
    }

    private static void setRunning(ConsumptionWorker w, List<ConsumptionWorker.Binding> bindings) throws Exception {
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
    void b1_consumptionLoopSkipsCleanPairsAndWakesOnDiscoveryOrReconcile() throws Exception {
        var def = new ConsumptionDefinition(new ConsumptionId(UUID.randomUUID()), ConsumableFamily.POT_EVENT, ConsumptionDefinition.Authority.READ);
        var proto = new StubProtocol(def);
        var binding = new ConsumptionWorker.Binding(def, proto, List.of(0));
        var w = worker(List.of(binding), Duration.ofMillis(60));
        setRunning(w, List.of(binding));
        var pair = new ConsumptionWorker.Pair(binding, 0);
        var cl = new ConsumptionLoop(w);
        // Pass 1: dirty by default -> calls navigate(), sees empty tourComplete -> marks clean.
        assertFalse(cl.runOnePass());
        assertEquals(1, proto.navigateCalls.get());
        assertTrue(w.isPairClean(pair, System.nanoTime()));
        // Pass 2 while clean: skips navigate() completely (still 1).
        assertFalse(cl.runOnePass());
        assertEquals(1, proto.navigateCalls.get());

        // DiscoveryLoop with committedAdmissions > 0 must mark pair dirty and wake ConsumptionLoop.
        proto.admissionsNext = 1;
        var dl = new DiscoveryLoop(w);
        assertTrue(dl.runOnePass());
        assertFalse(w.isPairClean(pair, System.nanoTime()));
        assertFalse(cl.runOnePass());
        assertEquals(2, proto.navigateCalls.get());

        // After clean TTL (60ms settleInterval), pair expires and reconciles via navigate().
        Thread.sleep(80);
        assertFalse(w.isPairClean(pair, System.nanoTime()));
        assertFalse(cl.runOnePass());
        assertEquals(3, proto.navigateCalls.get());
    }

    @Test
    void b3_discoveryLoopDeduplicatesSameFamilyEmptyCheckpointsWithinTour() throws Exception {
        var def1 = new ConsumptionDefinition(new ConsumptionId(UUID.randomUUID()), ConsumableFamily.POT_EVENT, ConsumptionDefinition.Authority.READ);
        var def2 = new ConsumptionDefinition(new ConsumptionId(UUID.randomUUID()), ConsumableFamily.POT_EVENT, ConsumptionDefinition.Authority.READ);
        var p1 = new StubProtocol(def1);
        var p2 = new StubProtocol(def2);
        var b1 = new ConsumptionWorker.Binding(def1, p1, List.of(0, 1));
        var b2 = new ConsumptionWorker.Binding(def2, p2, List.of(0, 1));
        var w = worker(List.of(b1, b2), Duration.ofMillis(500));
        setRunning(w, List.of(b1, b2));
        var dl = new DiscoveryLoop(w);

        // Tour 1 (4 passes) learns each pair's checkpoint (4 probes).
        for (int i = 0; i < 4; i++) dl.runOnePass();
        int totalFirstTour = p1.discoverCalls.get() + p2.discoverCalls.get();
        assertEquals(4, totalFirstTour);

        // Tour 2 (4 passes) skips 3 redundant same-(family, checkpoint) probes and re-probes on tour boundary (no starvation).
        for (int i = 0; i < 4; i++) dl.runOnePass();
        int totalTwoTours = p1.discoverCalls.get() + p2.discoverCalls.get();
        assertEquals(5, totalTwoTours);
    }
}
