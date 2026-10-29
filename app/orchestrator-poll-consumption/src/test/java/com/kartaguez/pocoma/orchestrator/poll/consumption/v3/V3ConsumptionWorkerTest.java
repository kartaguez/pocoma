package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Local concurrency proof only. Transactional/physical claims require the PostgreSQL suites. */
class V3ConsumptionWorkerTest {
    static final Duration WAIT = Duration.ofSeconds(5);

    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "condition deadline");
    }

    static void latch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static final AttemptTransactions TX =
            new AttemptTransactions() {
                public <T> T read(Function<RecoveryReadContext, T> f) {
                    return f.apply(() -> {});
                }

                public <T> T write(Supplier<T> f) {
                    return f.get();
                }

                public void poison(Throwable e) {}

                public void requirePublicationClosed() {}
            };

    static class Fixture implements AutoCloseable {
        final List<V3ConsumptionWorker.Binding> bindings = new ArrayList<>();
        final List<AtomicInteger> effects = new ArrayList<>();
        final List<Set<UUID>> keys = new ArrayList<>();
        final AtomicInteger discoveries = new AtomicInteger(), renewed = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final WorkerCapacity capacity;
        final V3ConsumptionWorker worker;
        final ThreadPoolExecutor executor;
        volatile Runnable beforeEffect = () -> {},
                beforeAcquire = () -> {},
                beforeRenew = () -> {},
                beforeLease = () -> {};
        volatile boolean unknown, failAcquire, zeroAcquire;

        Fixture(int count, int mw, boolean manual, ThreadPoolExecutor supplied) throws Exception {
            var limits = new LinkedHashMap<ConsumptionId, Integer>();
            for (int i = 0; i < count; i++) limits.put(new ConsumptionId(new UUID(0, i + 1)), mw);
            capacity = new WorkerCapacity(mw, limits);
            for (var id : limits.keySet()) {
                var definition =
                        new ConsumptionDefinition(
                                id,
                                ConsumableFamily.COMMAND,
                                ConsumptionDefinition.Authority.WRITE);
                var ids = ConcurrentHashMap.<UUID>newKeySet();
                ids.add(new UUID(1, 1));
                keys.add(ids);
                var granted = ConcurrentHashMap.<UUID>newKeySet();
                var done = new AtomicInteger();
                effects.add(done);
                var store =
                        (ConsumptionSlotStore)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {ConsumptionSlotStore.class},
                                        (p, m, a) ->
                                                switch (m.getName()) {
                                                    case "acquire" -> {
                                                        beforeAcquire.run();
                                                        if (failAcquire)
                                                            throw new AttemptTransactions.Failure(
                                                                    AttemptTransactions.Resolution
                                                                            .ROLLED_BACK,
                                                                    new RuntimeException());
                                                        if (zeroAcquire) yield List.of();
                                                        var lease = (PartitionLease) a[0];
                                                        var result =
                                                                new ArrayList<
                                                                        ConsumptionSlotStore
                                                                                .GrantedClaim>();
                                                        for (var candidate :
                                                                (List<
                                                                                ConsumptionSlotStore
                                                                                        .ClaimCandidate>)
                                                                        a[1])
                                                            if (ids.contains(
                                                                            candidate
                                                                                    .consumableId())
                                                                    && granted.add(
                                                                            candidate
                                                                                    .consumableId()))
                                                                result.add(
                                                                        new ConsumptionSlotStore
                                                                                .GrantedClaim(
                                                                                new ClaimedSlot(
                                                                                        id,
                                                                                        candidate
                                                                                                .consumableId(),
                                                                                        candidate
                                                                                                .freshToken()),
                                                                                Instant.now()
                                                                                        .plusSeconds(
                                                                                                60)));
                                                        yield result;
                                                    }
                                                    case "protectFinal" ->
                                                            ConsumptionSlotStore.Protection.VALID;
                                                    case "deleteProtected" -> {
                                                        ids.remove(
                                                                ((ClaimedSlot) a[0])
                                                                        .consumableId());
                                                        yield null;
                                                    }
                                                    case "renew" -> {
                                                        beforeRenew.run();
                                                        renewed.incrementAndGet();
                                                        yield ((List<ClaimedSlot>) a[0])
                                                                .stream()
                                                                        .map(
                                                                                c ->
                                                                                        new ConsumptionSlotStore
                                                                                                .GrantedClaim(
                                                                                                c,
                                                                                                Instant
                                                                                                        .now()
                                                                                                        .plusSeconds(
                                                                                                                60)))
                                                                        .toList();
                                                    }
                                                    case "inspectResolved" ->
                                                            ((List<ClaimedSlot>) a[0])
                                                                    .stream()
                                                                            .map(
                                                                                    c ->
                                                                                            new ConsumptionSlotStore
                                                                                                    .ClaimInspection(
                                                                                                    c,
                                                                                                    unknown
                                                                                                            ? ConsumptionSlotStore
                                                                                                                    .Inspection
                                                                                                                    .LOCKED
                                                                                                            : ConsumptionSlotStore
                                                                                                                    .Inspection
                                                                                                                    .ABSENT))
                                                                            .toList();
                                                    case "observe" ->
                                                            ids.isEmpty()
                                                                    ? ConsumptionSlotStore
                                                                            .SlotObservation.ABSENT
                                                                    : ConsumptionSlotStore
                                                                            .SlotObservation
                                                                            .PRESENT;
                                                    case "cede" -> Optional.empty();
                                                    default ->
                                                            throw new AssertionError(m.getName());
                                                });
                var ownership =
                        new PartitionOwnershipStore() {
                            public Optional<PartitionLease> acquire(
                                    ConsumptionDefinition d, int p, UUID token, Duration duration) {
                                beforeLease.run();
                                return Optional.of(
                                        new PartitionLease(
                                                d.id(), p, token, Instant.now().plusSeconds(30)));
                            }

                            public List<PartitionLease> renew(
                                    ConsumptionDefinition d,
                                    List<PartitionLease> l,
                                    Duration duration) {
                                return l;
                            }

                            public Optional<PartitionLease> protectAcquisition(
                                    ConsumptionDefinition d, PartitionLease l) {
                                return Optional.of(l);
                            }

                            public void bound(Duration l, Duration s) {}
                        };
                var participant =
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID key) {
                                return Optional.of(
                                        new Prepared() {
                                            public void finalizeIn(SlotCompletion completion) {
                                                beforeEffect.run();
                                                done.incrementAndGet();
                                                completion.complete();
                                                if (unknown)
                                                    throw new AttemptTransactions.Failure(
                                                            AttemptTransactions.Resolution.UNKNOWN,
                                                            new RuntimeException());
                                            }

                                            public void requireCompletionReady() {}
                                        });
                            }

                            public RecoveryObservation observe(UUID key, RecoveryReadContext c) {
                                return ids.isEmpty()
                                        ? RecoveryObservation.COMPLETED
                                        : RecoveryObservation.NOT_COMPLETED;
                            }
                        };
                SlotKeyNavigation navigation =
                        (c, p, walk, limit, context) -> {
                            var upper =
                                    walk.upperInclusive().isPresent()
                                            ? walk.upperInclusive()
                                            : ids.stream().max(SlotKeyNavigation::compare);
                            var page =
                                    ids.stream()
                                            .sorted(SlotKeyNavigation::compare)
                                            .filter(
                                                    k ->
                                                            upper.isPresent()
                                                                    && SlotKeyNavigation.compare(
                                                                                    k, upper.get())
                                                                            <= 0
                                                                    && (walk.afterExclusive()
                                                                                    .isEmpty()
                                                                            || SlotKeyNavigation
                                                                                            .compare(
                                                                                                    k,
                                                                                                    walk.afterExclusive()
                                                                                                            .get())
                                                                                    > 0))
                                            .limit(limit)
                                            .toList();
                            return new SlotKeyNavigation.Page(upper, page);
                        };
                var engine =
                        new ConsumptionEngine(
                                definition,
                                TX,
                                store,
                                participant,
                                TX,
                                store,
                                ownership,
                                LifecycleSettings.pilot(),
                                capacity,
                                c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                                navigation,
                                ConsumptionObservation.noop());
                var admissions =
                        (DiscoveryAdmissionStore)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {DiscoveryAdmissionStore.class},
                                        (p, m, a) ->
                                                switch (m.getName()) {
                                                    case "liveSlots" -> 0L;
                                                    case "contains" -> true;
                                                    case "insertInitial", "advanceCheckpoint" ->
                                                            null;
                                                    default ->
                                                            throw new AssertionError(m.getName());
                                                });
                var source =
                        new BurnableReferences() {
                            public ConsumableFamily family() {
                                return ConsumableFamily.COMMAND;
                            }

                            public Optional<UUID> upperPending(RecoveryReadContext c) {
                                discoveries.incrementAndGet();
                                return Optional.empty();
                            }

                            public List<UUID> pendingPage(
                                    Optional<UUID> a, UUID u, int n, RecoveryReadContext c) {
                                return List.of();
                            }

                            public boolean tryProtectPending(UUID key) {
                                return false;
                            }
                        };
                var discovery =
                        new DiscoveryEngine(
                                definition,
                                TX,
                                ownership,
                                admissions,
                                new DiscoveryEngine.Settings(
                                        32,
                                        32,
                                        32,
                                        100,
                                        Duration.ofMillis(10),
                                        Duration.ofMillis(20)),
                                source);
                bindings.add(
                        new V3ConsumptionWorker.Binding(definition, engine, discovery, List.of(0)));
            }
            executor =
                    supplied != null
                            ? supplied
                            : new ThreadPoolExecutor(
                                    mw,
                                    mw,
                                    0,
                                    TimeUnit.MILLISECONDS,
                                    new SynchronousQueue<>(),
                                    V3ConsumptionWorker.namedFactory("test-attempt"),
                                    new ThreadPoolExecutor.AbortPolicy());
            var settings =
                    new V3ConsumptionWorker.Settings(
                            32,
                            1,
                            Duration.ofMillis(2),
                            Duration.ofMillis(20),
                            Duration.ofMillis(10),
                            Duration.ofMillis(5),
                            Duration.ofMillis(20),
                            Duration.ofSeconds(3),
                            Duration.ofMillis(100));
            worker =
                    new V3ConsumptionWorker(
                            bindings,
                            capacity,
                            limits,
                            settings,
                            ConsumptionObservation.noop(),
                            closes::incrementAndGet,
                            executor);
            executor.prestartAllCoreThreads();
            await(
                    () ->
                            Thread.getAllStackTraces().keySet().stream()
                                            .filter(
                                                    t ->
                                                            (t.getName().startsWith("test-attempt-")
                                                                            || t.getName()
                                                                                    .startsWith(
                                                                                            "paused-"))
                                                                    && t.getState()
                                                                            == Thread.State.WAITING)
                                            .count()
                                    >= mw);
            if (manual) {
                var state = V3ConsumptionWorker.class.getDeclaredField("state");
                state.setAccessible(true);
                state.set(worker, V3ConsumptionWorker.State.RUNNING);
                executor.prestartAllCoreThreads();
            } else worker.start();
        }

        public void close() throws Exception {
            beforeEffect = () -> {};
            beforeRenew = () -> {};
            unknown = false;
            worker.requestDrain();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            for (var b : bindings) b.engine().settlePending();
            worker.prune();
        }
    }

    @Test
    void i5_u01() throws Exception {
        try (var f = new Fixture(3, 1, true, null)) {
            var loop = new V3ConsumptionLoop(f.worker);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            f.beforeEffect =
                    () -> {
                        entered.countDown();
                        latch(release);
                    };
            try {
                loop.runOnePass();
                latch(entered);
                assertEquals(1, loop.nextConsumption());
                for (int i = 0; i < 10; i++) assertFalse(loop.runOnePass());
                assertEquals(1, loop.nextConsumption());
            } finally {
                release.countDown();
            }
            await(() -> f.capacity.snapshot().worker() == 0 && f.executor.getActiveCount() == 0);
            f.beforeEffect = () -> {};
            loop.runOnePass();
            await(() -> f.effects.get(1).get() == 1);
            assertEquals(2, loop.nextConsumption());
            loop.runOnePass();
            await(() -> f.effects.get(2).get() == 1);
            assertEquals(List.of(1, 1, 1), f.effects.stream().map(AtomicInteger::get).toList());
        }
    }

    @Test
    void i5_u02() throws Exception {
        try (var f = new Fixture(1, 1, true, null)) {
            var loop = new V3ConsumptionLoop(f.worker);
            var b = f.bindings.getFirst();
            f.failAcquire = true;
            assertThrows(AttemptTransactions.Failure.class, loop::runOnePass);
            assertEquals(Optional.empty(), loop.walk(b, 0).afterExclusive());
            assertEquals(0, f.capacity.snapshot().worker());
            f.failAcquire = false;
            f.zeroAcquire = true;
            loop.runOnePass();
            // A scanned but unwon key is examined (not a fabricated completion).
            assertEquals(Optional.of(new UUID(1, 1)), loop.walk(b, 0).afterExclusive());
            assertEquals(0, f.effects.getFirst().get());
        }
    }

    @Test
    void i5_u03() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        V3ConsumptionWorker.namedFactory("paused"),
                        new ThreadPoolExecutor.AbortPolicy()) {
                    @Override
                    protected void beforeExecute(Thread t, Runnable r) {
                        accepted.countDown();
                        latch(release);
                    }
                };
        try (var f = new Fixture(1, 1, true, executor)) {
            new V3ConsumptionLoop(f.worker).runOnePass();
            latch(accepted);
            f.worker.requestDrain();
            f.worker.requestDrain();
            assertEquals(0, f.capacity.snapshot().worker());
            assertEquals(0, f.effects.getFirst().get());
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            f.worker.prune();
            assertEquals(0, f.worker.snapshot().handles());
        } finally {
            release.countDown();
        }
        try (var f = new Fixture(1, 1, true, null)) {
            var b = f.bindings.getFirst();
            var lease = b.engine().acquirePartition(0).orElseThrow();
            var handle = b.engine().acquire(lease, List.of(new UUID(1, 1))).claims().getFirst();
            f.executor.shutdown();
            f.worker.dispatch(b, List.of(handle));
            assertEquals(0, f.capacity.snapshot().worker());
            assertEquals(0, f.effects.getFirst().get());
        }
    }

    @Test
    void i5_u04() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var f = new Fixture(1, 1, true, null)) {
            f.beforeEffect =
                    () -> {
                        entered.countDown();
                        latch(release);
                    };
            new V3ConsumptionLoop(f.worker).runOnePass();
            latch(entered);
            var discovery = new V3DiscoveryLoop(f.worker);
            discovery.runOnePass();
            assertTrue(f.discoveries.get() > 0);
            Thread.sleep(25); // Due time only, not evidence of a transactional interleaving.
            assertTrue(
                    new V3RenewalService(f.worker)
                            .renewClaimsOnce(ConsumptionDefinition.Authority.WRITE, 0));
            assertEquals(1, f.renewed.get());
            assertEquals(1, f.capacity.snapshot().worker());
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void i5_u05() throws Exception {
        try (var f = new Fixture(1, 1, true, null)) {
            var state = V3ConsumptionWorker.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(f.worker, V3ConsumptionWorker.State.STARTING);
            var startup = V3ConsumptionWorker.class.getDeclaredField("startup");
            startup.setAccessible(true);
            var acknowledged = new CountDownLatch(7);
            startup.set(f.worker, acknowledged);
            var starting = new Thread(f.worker::start, "i5-starting-test");
            starting.start();
            await(() -> acknowledged.getCount() == 1);
            var callbacks = new AtomicInteger();
            f.worker.requestDrain(callbacks::incrementAndGet);
            acknowledged.countDown();
            starting.join(5000);
            assertFalse(starting.isAlive());
            await(
                    () ->
                            f.worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED
                                    && callbacks.get() == 1);
            assertEquals(0, f.discoveries.get());
            assertEquals(0, f.effects.getFirst().get());
            assertEquals(0, f.capacity.snapshot().worker());
        }
        try (var f = new Fixture(1, 1, false, null)) {
            await(() -> f.effects.getFirst().get() == 1);
            var callbacks = new AtomicInteger();
            f.worker.requestDrain(callbacks::incrementAndGet);
            f.worker.requestDrain(callbacks::incrementAndGet);
            await(
                    () ->
                            f.worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED
                                    && callbacks.get() == 2);
            assertEquals(2, callbacks.get());
            assertEquals(1, f.closes.get());
            assertFalse(f.worker.isRunning());
        }
    }

    @Test
    void i5_u06() throws Exception {
        try (var f = new Fixture(1, 1, false, null)) {
            await(() -> f.effects.getFirst().get() == 1);
            int before = f.discoveries.get();
            Thread.sleep(100);
            assertTrue(f.discoveries.get() - before < 20, "idle polling must back off");
            f.worker.requestDrain();
            await(() -> f.worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED);
        }
    }

    @Test
    void i5_u07() throws Exception {
        try (var f = new Fixture(1, 1, true, null)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            f.beforeEffect =
                    () -> {
                        entered.countDown();
                        latch(release);
                    };
            new V3ConsumptionLoop(f.worker).runOnePass();
            latch(entered);
            Thread.sleep(25);
            var renewing = new CountDownLatch(1);
            var renewReturn = new CountDownLatch(1);
            f.beforeRenew =
                    () -> {
                        renewing.countDown();
                        latch(renewReturn);
                    };
            var renewal = new V3RenewalService(f.worker);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var call =
                        threads.submit(
                                () ->
                                        renewal.renewClaimsOnce(
                                                ConsumptionDefinition.Authority.WRITE, 0));
                latch(renewing);
                // SQL/callback can block while the local monitor remains available.
                threads.submit(f.worker::snapshot).get(1, TimeUnit.SECONDS);
                release.countDown();
                await(
                        () ->
                                f.capacity.snapshot().worker() == 0
                                        && f.executor.getActiveCount() == 0);
                f.worker.prune();
                assertEquals(0, f.worker.snapshot().handles());
                renewReturn.countDown();
                assertTrue(call.get(5, TimeUnit.SECONDS));
                assertEquals(0, f.worker.snapshot().handles());
                assertEquals(0, f.capacity.snapshot().worker());
                assertEquals(1, f.effects.getFirst().get());
            } finally {
                release.countDown();
                renewReturn.countDown();
            }
        }
    }

    @Test
    void i5_u08() throws Exception {
        try (var f = new Fixture(2, 1, true, null)) {
            for (var binding : f.bindings) f.worker.lease(binding, 0);
            for (var entry : f.worker.leases.values())
                entry.renewedAt = System.nanoTime() - Duration.ofSeconds(10).toNanos();
            var renewals = new V3RenewalService(f.worker);
            var visited = new HashSet<V3ConsumptionWorker.LeaseEntry>();
            for (int round = 0; round < 4; round++) {
                var before = new IdentityHashMap<V3ConsumptionWorker.LeaseEntry, Long>();
                for (var entry : f.worker.leases.values()) before.put(entry, entry.renewedAt);
                assertTrue(renewals.renewLeasesOnce(ConsumptionDefinition.Authority.WRITE));
                var changed =
                        f.worker.leases.values().stream()
                                .filter(entry -> entry.renewedAt != before.get(entry))
                                .toList();
                assertEquals(1, changed.size());
                visited.addAll(changed);
                // Deterministic elapsed opportunities: a serviced lease is due again while
                // another is older. This proves local selection, never a PostgreSQL deadline.
                for (var entry : f.worker.leases.values())
                    entry.renewedAt -= f.worker.settings.renewalInterval().toNanos() + 1;
            }
            assertEquals(
                    2,
                    visited.size(),
                    "no fixed-binding starvation when a lease becomes due again");
            assertEquals(0, f.capacity.snapshot().worker());
            assertEquals(0, f.worker.snapshot().renewalCalls());
            assertEquals(0, f.effects.stream().mapToInt(AtomicInteger::get).sum());
        }
    }

    @Test
    void i5_u09() throws Exception {
        for (boolean discovery : List.of(true, false)) {
            for (boolean raw : List.of(true, false)) {
                try (var f = new Fixture(1, 1, true, null)) {
                    f.beforeLease =
                            () -> {
                                if (raw) throw new IllegalStateException("unqualified local exit");
                                throw new AttemptTransactions.Failure(
                                        AttemptTransactions.Resolution.UNKNOWN,
                                        new IllegalStateException("physical unit unresolved"));
                            };
                    assertThrows(
                            RuntimeException.class,
                            () -> {
                                if (discovery) new V3DiscoveryLoop(f.worker).runOnePass();
                                else new V3ConsumptionLoop(f.worker).runOnePass();
                            });
                    assertEquals(V3ConsumptionWorker.State.DRAINING, f.worker.snapshot().state());
                    assertEquals(1, f.worker.snapshot().operations());
                    assertEquals(0, f.capacity.snapshot().worker());
                    f.bindings.getFirst().engine().settlePending();
                    f.worker.prune();
                    assertEquals(1, f.worker.snapshot().operations());
                    assertEquals(0, f.closes.get());
                }
            }
            try (var f = new Fixture(1, 1, true, null)) {
                f.beforeLease =
                        () -> {
                            throw new AttemptTransactions.Failure(
                                    AttemptTransactions.Resolution.ROLLED_BACK,
                                    new IllegalStateException("known rollback"));
                        };
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () -> {
                            if (discovery) new V3DiscoveryLoop(f.worker).runOnePass();
                            else new V3ConsumptionLoop(f.worker).runOnePass();
                        });
                assertEquals(0, f.worker.snapshot().operations());
                assertEquals(V3ConsumptionWorker.State.RUNNING, f.worker.snapshot().state());
            }
        }
    }
}
