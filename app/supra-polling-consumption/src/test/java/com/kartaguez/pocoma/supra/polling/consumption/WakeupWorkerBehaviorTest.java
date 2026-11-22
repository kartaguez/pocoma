package com.kartaguez.pocoma.supra.polling.consumption;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports;
import com.kartaguez.pocoma.contract.consumption.invocation.ProtocolSafety;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupSubscriber;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.wait.ConditionConsumptionWaiter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WakeupWorkerBehaviorTest {

    private static final class RecordingWakeupMetrics implements WakeupMetrics {
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger useful = new AtomicInteger();
        final AtomicInteger spurious = new AtomicInteger();
        final AtomicInteger ignoredNoLease = new AtomicInteger();
        final AtomicInteger fallbackTransitions = new AtomicInteger();
        final AtomicInteger reconnects = new AtomicInteger();
        final AtomicInteger discoveryDelaySamples = new AtomicInteger();
        final List<WakeupState> states = new CopyOnWriteArrayList<>();

        @Override
        public void onReceived() {
            received.incrementAndGet();
        }

        @Override
        public void onWakeupUseful() {
            useful.incrementAndGet();
        }

        @Override
        public void onWakeupSpurious() {
            spurious.incrementAndGet();
        }

        @Override
        public void onWakeupIgnoredNoLease() {
            ignoredNoLease.incrementAndGet();
        }

        @Override
        public void onFallbackTransition() {
            fallbackTransitions.incrementAndGet();
        }

        @Override
        public void onReconnect() {
            reconnects.incrementAndGet();
        }

        @Override
        public void onStateChanged(WakeupState state) {
            states.add(state);
        }

        @Override
        public void onDiscoveryDelay(long nanos) {
            if (nanos >= 0) discoveryDelaySamples.incrementAndGet();
        }
    }

    private static final class WorkProtocol implements ConsumptionProtocol {
        final ConsumptionDefinition def;
        final AtomicInteger discoverCalls = new AtomicInteger();
        final AtomicInteger executedCount = new AtomicInteger();
        final AtomicBoolean allowLease = new AtomicBoolean(true);
        final AtomicInteger pendingAdmissions = new AtomicInteger();
        final List<UUID> readySlots = new CopyOnWriteArrayList<>();
        volatile long checkpoint = 10L;

        WorkProtocol(ConsumptionDefinition def) {
            this.def = def;
        }

        @Override
        public Optional<Partition> acquirePartition(int partition) {
            if (!allowLease.get()) return Optional.empty();
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
        public void renewClaim(Handle handle) {}

        @Override
        public Navigation navigate(Partition p, ConsumptionNavigation.Walk w, int limit) {
            if (readySlots.isEmpty()) {
                return new Navigation(List.of(), ConsumptionNavigation.Walk.fresh(), true);
            }
            return new Navigation(List.copyOf(readySlots), ConsumptionNavigation.Walk.fresh(), false);
        }

        @Override
        public Acquisition acquire(Partition p, List<UUID> keys) {
            return acquire(p, keys, ConsumptionNavigation.Walk.fresh());
        }

        @Override
        public Acquisition acquire(Partition p, List<UUID> keys, ConsumptionNavigation.Walk c) {
            var handles = new ArrayList<Handle>();
            for (UUID key : keys) {
                if (readySlots.remove(key)) {
                    handles.add(
                            new Handle() {
                                @Override
                                public ConsumptionId consumptionId() {
                                    return def.id();
                                }

                                @Override
                                public UUID consumableId() {
                                    return key;
                                }

                                @Override
                                public int partitionId() {
                                    return p.partitionId();
                                }
                            });
                }
            }
            return new Acquisition(handles, handles.size(), Optional.of(c), false);
        }

        @Override
        public Attempt execute(Handle handle) {
            executedCount.incrementAndGet();
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
            return discover(partition, true);
        }

        @Override
        public DiscoveryReports.Progress discover(
                Partition partition, boolean verifyOwnershipOnEmpty) {
            discoverCalls.incrementAndGet();
            int adm = pendingAdmissions.getAndSet(0);
            if (adm > 0) {
                checkpoint += adm;
                for (int i = 0; i < adm; i++) {
                    readySlots.add(UUID.randomUUID());
                }
            }
            return new DiscoveryReports.Progress(
                    DiscoveryReports.Status.COMMITTED, OptionalLong.of(checkpoint), adm, 0, adm);
        }
    }

    private static ConsumptionWorker newWorker(
            List<ConsumptionWorker.Binding> bindings,
            ConsumptionWorker.WakeupOptions wakeupOptions) {
        var caps = new HashMap<ConsumptionId, Integer>();
        for (var b : bindings) caps.put(b.definition().id(), 2);
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
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30));
        ProtocolSafety safety =
                new ProtocolSafety() {
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
                            ConsumptionDefinition.Authority a,
                            int lane,
                            ConsumptionProtocol.Handle c) {
                        return Optional.of(new RenewalPermit() {});
                    }

                    @Override
                    public PhysicalOutcome finishRenewal(RenewalPermit p, Throwable f) {
                        return new PhysicalOutcome(
                                AttemptTransactions.Resolution.COMMITTED, true, false);
                    }

                    @Override
                    public LeaseDecision settleLease(
                            ConsumptionProtocol.Partition c,
                            List<ConsumptionProtocol.Partition> r,
                            PhysicalOutcome o,
                            boolean running,
                            boolean usable,
                            long ru,
                            long rd,
                            long due,
                            long cb,
                            long ra,
                            Duration rb,
                            Duration ri,
                            Duration mp) {
                        return new LeaseDecision(false, c, usable, ru, rd, ra, false);
                    }
                };
        return new ConsumptionWorker(
                bindings,
                cap,
                caps,
                settings,
                ConsumptionObservation.noop(),
                () -> {},
                com.kartaguez.pocoma.contract.consumption.observation.AttemptObservation.NOOP,
                (protocols, capacity) -> safety,
                wakeupOptions);
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
    void conditionWaiterLatchesSignalBeforeAwait() throws Exception {
        var waiter = new ConditionConsumptionWaiter();
        waiter.signal();
        long start = System.nanoTime();
        waiter.await(Duration.ofSeconds(10));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 200, "Latched signal must return immediately, took " + elapsedMs + "ms");
    }

    @Test
    void conditionWaiterSecondAwaitSleepsNormallyAfterLatchConsumed() throws Exception {
        var waiter = new ConditionConsumptionWaiter();
        waiter.signal();
        waiter.await(Duration.ofSeconds(5));
        long start = System.nanoTime();
        waiter.await(Duration.ofMillis(40));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 25, "Latch must be consumed after first await, took " + elapsedMs + "ms");
    }

    @Test
    void noopSubscriberReportsNoopState() {
        var observed = new AtomicReference<WakeupState>();
        WakeupSubscriber.NOOP.start(Set.of(), k -> {}, observed::set);
        assertEquals(WakeupState.NOOP, observed.get());
        assertEquals(WakeupState.NOOP, WakeupSubscriber.NOOP.state());
    }

    @Test
    void discoveryLoopExternalWakeupInvalidatesEmptyFamilyCacheAndRecordsDelay() throws Exception {
        var def1 =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var def2 =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var p1 = new WorkProtocol(def1);
        var p2 = new WorkProtocol(def2);
        var b1 = new ConsumptionWorker.Binding(def1, p1, List.of(0));
        var b2 = new ConsumptionWorker.Binding(def2, p2, List.of(0));
        var metrics = new RecordingWakeupMetrics();
        var worker =
                newWorker(
                        List.of(b1, b2),
                        new ConsumptionWorker.WakeupOptions(
                                WakeupSubscriber.NOOP,
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                Duration.ofMillis(20),
                                metrics));
        markRunning(worker, List.of(b1, b2));
        var dl = new DiscoveryLoop(worker);

        // Tour 1 caches empty checkpoint for POT_EVENT family across b1 and b2.
        dl.runOnePass();
        dl.runOnePass();
        assertEquals(1, p1.discoverCalls.get());
        assertEquals(1, p2.discoverCalls.get());

        // Without external wakeup, cached empty checkpoint skips b1's probe in tour 2.
        dl.runOnePass();
        assertEquals(1, p1.discoverCalls.get(), "Empty family cache should skip redundant probe");

        // External wakeup for POT_EVENT partition 0 must invalidate cache immediately!
        p1.pendingAdmissions.set(1);
        p2.pendingAdmissions.set(1);
        dl.onExternalWakeup(ConsumableFamily.POT_EVENT, 0);
        assertTrue(dl.hasPendingWakeup());
        assertTrue(dl.runOnePass());
        assertTrue(dl.runOnePass());
        assertEquals(2, p1.discoverCalls.get());
        assertEquals(2, p2.discoverCalls.get());
        assertEquals(2, metrics.useful.get());
        assertEquals(2, metrics.discoveryDelaySamples.get());
    }

    @Test
    void discoveryLoopExternalReconnectInvalidatesEmptyFamilyCache() throws Exception {
        var def1 =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var def2 =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var p1 = new WorkProtocol(def1);
        var p2 = new WorkProtocol(def2);
        var b1 = new ConsumptionWorker.Binding(def1, p1, List.of(0));
        var b2 = new ConsumptionWorker.Binding(def2, p2, List.of(0));
        var worker =
                newWorker(
                        List.of(b1, b2),
                        new ConsumptionWorker.WakeupOptions(
                                WakeupSubscriber.NOOP,
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                Duration.ofMillis(20),
                                WakeupMetrics.NOOP));
        markRunning(worker, List.of(b1, b2));
        var dl = new DiscoveryLoop(worker);

        dl.runOnePass();
        dl.runOnePass();
        int beforeReconnect = p1.discoverCalls.get() + p2.discoverCalls.get();

        dl.onExternalReconnect();
        assertTrue(dl.hasPendingWakeup());
        dl.runOnePass();
        assertEquals(
                beforeReconnect + 1,
                p1.discoverCalls.get() + p2.discoverCalls.get(),
                "Reconnect must invalidate emptyFamilyCheckpointThisTour");
    }

    @Test
    void discoveryLoopSimultaneousDuplicateWakeupsCoalesceWithoutLoss() throws Exception {
        var def =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.COMMAND,
                        ConsumptionDefinition.Authority.WRITE);
        var proto = new WorkProtocol(def);
        var binding = new ConsumptionWorker.Binding(def, proto, List.of(0, 1));
        var metrics = new RecordingWakeupMetrics();
        var worker =
                newWorker(
                        List.of(binding),
                        new ConsumptionWorker.WakeupOptions(
                                WakeupSubscriber.NOOP,
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                Duration.ofMillis(20),
                                metrics));
        markRunning(worker, List.of(binding));
        var dl = new DiscoveryLoop(worker);

        int threads = 8;
        int iterations = 250;
        var startGate = new CountDownLatch(1);
        var doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int partition = t % 2;
                pool.submit(
                        () -> {
                            try {
                                startGate.await(5, TimeUnit.SECONDS);
                                for (int i = 0; i < iterations; i++) {
                                    dl.onExternalWakeup(ConsumableFamily.COMMAND, partition);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                doneGate.countDown();
                            }
                        });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * iterations, metrics.received.get());
        proto.pendingAdmissions.set(1);
        assertTrue(dl.runOnePass(), "Partition 0 bit coalesced into single wakeup");
        assertFalse(dl.runOnePass(), "Partition 1 bit coalesced into single wakeup");
        assertFalse(dl.hasPendingWakeup(), "Bitmask must be empty after draining the 2 unique bits");
        assertEquals(2, proto.discoverCalls.get());
    }

    @Test
    void discoveryLoopWakeupIgnoredSafelyWhenLeaseLost() throws Exception {
        var def =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        var proto = new WorkProtocol(def);
        var binding = new ConsumptionWorker.Binding(def, proto, List.of(0));
        var metrics = new RecordingWakeupMetrics();
        var worker =
                newWorker(
                        List.of(binding),
                        new ConsumptionWorker.WakeupOptions(
                                WakeupSubscriber.NOOP,
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                Duration.ofMillis(20),
                                metrics));
        markRunning(worker, List.of(binding));
        // Simulate lease loss: remove lease and block re-acquisition.
        worker.leases.clear();
        proto.allowLease.set(false);

        var dl = new DiscoveryLoop(worker);
        dl.onExternalWakeup(ConsumableFamily.POT_EVENT, 0);
        assertFalse(dl.runOnePass());
        assertEquals(0, proto.discoverCalls.get(), "Must not discover without owned lease");
        assertEquals(1, metrics.ignoredNoLease.get());
    }

    @Test
    void crashAfterCommitWithoutNotificationProgressesViaReconcilePollAndNoop() throws Exception {
        for (boolean noopMode : List.of(false, true)) {
            var def =
                    new ConsumptionDefinition(
                            new ConsumptionId(UUID.randomUUID()),
                            ConsumableFamily.COMMAND,
                            ConsumptionDefinition.Authority.WRITE);
            var proto = new WorkProtocol(def);
            var binding = new ConsumptionWorker.Binding(def, proto, List.of(0));
            var stateCallback = new AtomicReference<Consumer<WakeupState>>();
            var metrics = new RecordingWakeupMetrics();

            WakeupSubscriber subscriber =
                    noopMode
                            ? WakeupSubscriber.NOOP
                            : new WakeupSubscriber() {
                                private volatile WakeupState st = WakeupState.DEGRADED;

                                @Override
                                public void start(
                                        Set<WakeupKey> keys,
                                        Consumer<WakeupKey> onWakeup,
                                        Consumer<WakeupState> onStateChange) {
                                    stateCallback.set(
                                            next -> {
                                                st = next;
                                                onStateChange.accept(next);
                                            });
                                    stateCallback.get().accept(WakeupState.DEGRADED);
                                }

                                @Override
                                public WakeupState state() {
                                    return st;
                                }

                                @Override
                                public void close() {}
                            };

            var worker =
                    newWorker(
                            List.of(binding),
                            new ConsumptionWorker.WakeupOptions(
                                    subscriber,
                                    Duration.ofMillis(40), // reconcilePoll in CONNECTED
                                    Duration.ofMillis(10), // fallbackMaxPoll in DEGRADED
                                    Duration.ofMillis(10),
                                    metrics));
            worker.start();
            try {
                if (!noopMode) {
                    // Starts DEGRADED -> nominal polling must NOT be active yet.
                    assertEquals(WakeupState.DEGRADED, worker.wakeupState());
                    // Transition to CONNECTED (subscriptions confirmed) -> reconcilePoll becomes active.
                    stateCallback.get().accept(WakeupState.CONNECTED);
                    assertEquals(WakeupState.CONNECTED, worker.wakeupState());
                    assertEquals(1, metrics.reconnects.get());
                } else {
                    assertEquals(WakeupState.NOOP, worker.wakeupState());
                }

                // Simulate committed work in PostgreSQL where publisher crashed right after COMMIT
                // (ZERO external wakeup notifications are ever sent).
                proto.pendingAdmissions.set(1);

                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (proto.executedCount.get() < 1 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(
                        1,
                        proto.executedCount.get(),
                        "Work committed without notification must be discovered and executed exactly once (noop="
                                + noopMode
                                + ")");
                // Verify no duplicate execution occurs on subsequent reconcile polls.
                Thread.sleep(100);
                assertEquals(1, proto.executedCount.get());
            } finally {
                var stopped = new CountDownLatch(1);
                worker.requestDrain(stopped::countDown);
                assertTrue(stopped.await(5, TimeUnit.SECONDS), "Worker must drain cleanly");
            }
        }
    }
}
