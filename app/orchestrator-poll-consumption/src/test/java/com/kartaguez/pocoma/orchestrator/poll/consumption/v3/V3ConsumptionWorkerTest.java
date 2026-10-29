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
            this(count, mw, manual, supplied, ConsumptionObservation.noop());
        }

        Fixture(
                int count,
                int mw,
                boolean manual,
                ThreadPoolExecutor supplied,
                ConsumptionObservation observation)
                throws Exception {
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
                            observation,
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

    static boolean awaitingHandoff(Fixture f) {
        return f.capacity.snapshot().worker() == 0
                && f.executor.getActiveCount() == 0
                && Thread.getAllStackTraces().entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey().getName().startsWith("test-attempt-")
                                                && e.getKey().getState() == Thread.State.WAITING
                                                && Arrays.stream(e.getValue())
                                                        .anyMatch(
                                                                frame ->
                                                                        frame.getClassName()
                                                                                .startsWith(
                                                                                        "java.util.concurrent.SynchronousQueue")));
    }

    @Test
    void i5_u10() throws Exception {
        try (var f = new Fixture(1, 1, true, null)) {
            var binding = f.bindings.getFirst();
            var lease = f.worker.lease(binding, 0);
            var pair = new V3ConsumptionWorker.Pair(binding, 0);
            var entry = f.worker.leases.get(pair);
            var originalField = ConsumptionEngine.class.getDeclaredField("ownership");
            originalField.setAccessible(true);
            var original = (PartitionOwnershipStore) originalField.get(binding.engine());
            var calls = new AtomicInteger();
            var alwaysMissing = new AtomicBoolean();
            originalField.set(
                    binding.engine(),
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {PartitionOwnershipStore.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("renew")
                                        && (calls.incrementAndGet() == 1 || alwaysMissing.get()))
                                    return List.of();
                                try {
                                    return method.invoke(original, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            }));
            entry.renewedAt = System.nanoTime() - f.worker.settings.renewalInterval().toNanos();
            var renewal = new V3RenewalService(f.worker);
            assertTrue(renewal.renewLeasesOnce(binding.definition().authority()));
            assertSame(
                    entry,
                    f.worker.leases.get(pair),
                    "SKIP LOCKED omission is not proof of lease loss");
            assertFalse(entry.usable);
            assertNull(f.worker.lease(binding, 0));
            await(
                    () ->
                            System.nanoTime() - entry.renewedAt
                                    >= f.worker.settings.renewalInterval().toNanos());
            assertTrue(renewal.renewLeasesOnce(binding.definition().authority()));
            assertSame(entry, f.worker.leases.get(pair));
            assertTrue(entry.usable);
            assertEquals(2, calls.get());
            assertEquals(0, f.capacity.snapshot().worker());
            alwaysMissing.set(true);
            entry.renewedAt = System.nanoTime() - f.worker.settings.renewalInterval().toNanos();
            assertTrue(renewal.renewLeasesOnce(binding.definition().authority()));
            assertSame(entry, f.worker.leases.get(pair));
            assertFalse(entry.usable);
            var budget = entry.getClass().getDeclaredField("retryUntil");
            budget.setAccessible(true);
            budget.setLong(entry, System.nanoTime() - 1);
            entry.renewedAt = System.nanoTime() - f.worker.settings.renewalInterval().toNanos();
            assertTrue(renewal.renewLeasesOnce(binding.definition().authority()));
            assertFalse(
                    f.worker.leases.containsKey(pair),
                    "bounded local retry expires without asserting durable loss");
            assertEquals(0, f.effects.getFirst().get());
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
            await(() -> awaitingHandoff(f));
            f.beforeEffect = () -> {};
            loop.runOnePass();
            await(() -> f.effects.get(1).get() == 1);
            assertEquals(2, loop.nextConsumption());
            await(() -> awaitingHandoff(f));
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
    void i5_v2_selector_bound() throws Exception {
        // Use the real service. Every older contender omits repeatedly; no R limit.
        for (int configuration : List.of(2, 4, 8, -8)) {
            int n = Math.abs(configuration);
            try (var f = new Fixture(n, 1, true, null)) {
                for (var binding : f.bindings) f.worker.lease(binding, 0);
                var entries = new ArrayList<>(f.worker.leases.values());
                long base = System.nanoTime() - Duration.ofSeconds(1).toNanos();
                for (int i = 0; i < n; i++) {
                    var entry = entries.get(i);
                    entry.renewedAt = base + (configuration < 0 ? 0 : i); // Include all ties.
                    var engine = entry.pair.binding().engine();
                    var member = ConsumptionEngine.class.getDeclaredField("ownership");
                    member.setAccessible(true);
                    var owner = (PartitionOwnershipStore) member.get(engine);
                    member.set(
                            engine,
                            Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {PartitionOwnershipStore.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("renew")) return List.of();
                                        try {
                                            return method.invoke(owner, args);
                                        } catch (InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                    }));
                }
                var service = new V3RenewalService(f.worker);
                var visited = new HashSet<V3ConsumptionWorker.LeaseEntry>();
                for (int step = 0; step < n; step++) {
                    var before = new IdentityHashMap<V3ConsumptionWorker.LeaseEntry, Long>();
                    entries.forEach(e -> before.put(e, e.renewedAt));
                    assertTrue(service.renewLeasesOnce(ConsumptionDefinition.Authority.WRITE));
                    var changed =
                            entries.stream().filter(e -> e.renewedAt != before.get(e)).toList();
                    assertEquals(1, changed.size());
                    assertTrue(
                            visited.add(changed.getFirst()),
                            "omitted contender jumped ahead twice");
                }
                assertEquals(n, visited.size());
                assertFalse(entries.getLast().usable);
                assertEquals(0, f.worker.snapshot().renewalCalls());
                assertEquals(0, f.capacity.snapshot().worker());
            }
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

    /**
     * Independent ordered source and owner-return journal; expected tours are fixed by each test.
     */
    static final class TourFixture implements AutoCloseable {
        final List<String> journal = new ArrayList<>();
        final List<UUID> source = new ArrayList<>();
        final Set<UUID> admitted = new HashSet<>();
        final AtomicInteger tours = new AtomicInteger();
        final Fixture base;
        final V3DiscoveryLoop loop;
        boolean full, noLease, notOwner, sourceFailure, admissionFailure, fenceFailure;
        boolean observerFailure;
        AttemptTransactions.Resolution lostResult;
        int protections;
        long checkpoint;

        TourFixture(ConsumableFamily family, int count, int size, int pageSize, int softCap)
                throws Exception {
            base =
                    new Fixture(
                            count,
                            1,
                            true,
                            null,
                            (id, phase, outcome, amount, elapsed) -> {
                                if (phase == ConsumptionObservation.Phase.DISCOVERY_TOUR) {
                                    journal.add("metric:" + id.value());
                                    tours.addAndGet((int) amount);
                                }
                                if (observerFailure)
                                    throw new AssertionError("observer unavailable");
                            });
            for (long n = 1; source.size() < size; n++) {
                var key = new UUID(0, n);
                if (RoutingV1.partition(RoutingV1.hash(family, key), 4) == 0) source.add(key);
            }
            var bindings = new ArrayList<V3ConsumptionWorker.Binding>();
            for (var original : base.bindings) {
                var definition =
                        new ConsumptionDefinition(
                                original.definition().id(),
                                family,
                                family == ConsumableFamily.COMMAND
                                                || family == ConsumableFamily.IDENTITY_REQUEST
                                        ? ConsumptionDefinition.Authority.WRITE
                                        : ConsumptionDefinition.Authority.READ);
                var ownership =
                        new PartitionOwnershipStore() {
                            public Optional<PartitionLease> acquire(
                                    ConsumptionDefinition d, int p, UUID token, Duration ttl) {
                                return noLease
                                        ? Optional.empty()
                                        : Optional.of(
                                                new PartitionLease(
                                                        d.id(),
                                                        p,
                                                        token,
                                                        Instant.now().plusSeconds(90)));
                            }

                            public List<PartitionLease> renew(
                                    ConsumptionDefinition d,
                                    List<PartitionLease> leases,
                                    Duration ttl) {
                                return leases;
                            }

                            public Optional<PartitionLease> protectAcquisition(
                                    ConsumptionDefinition d, PartitionLease lease) {
                                protections++;
                                if (fenceFailure && protections > 1)
                                    throw new IllegalStateException("fence");
                                return notOwner ? Optional.empty() : Optional.of(lease);
                            }

                            public void bound(Duration lock, Duration statement) {}
                        };
                var admissions =
                        (DiscoveryAdmissionStore)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {DiscoveryAdmissionStore.class},
                                        (p, m, args) ->
                                                switch (m.getName()) {
                                                    case "liveSlots" ->
                                                            full
                                                                    ? (long) softCap
                                                                    : (long) admitted.size();
                                                    case "contains" ->
                                                            admitted.contains((UUID) args[2]);
                                                    case "insertInitial" -> {
                                                        if (admissionFailure)
                                                            throw new IllegalStateException(
                                                                    "admission");
                                                        admitted.add((UUID) args[2]);
                                                        yield null;
                                                    }
                                                    case "checkpoint", "currentCheckpoint" ->
                                                            checkpoint;
                                                    case "advanceCheckpoint" -> {
                                                        checkpoint = (long) args[2];
                                                        yield null;
                                                    }
                                                    default ->
                                                            throw new AssertionError(m.getName());
                                                });
                var owner =
                        new AttemptTransactions() {
                            public <T> T read(Function<RecoveryReadContext, T> body) {
                                return body.apply(() -> {});
                            }

                            public <T> T write(Supplier<T> body) {
                                journal.add("owner-enter");
                                T result = body.get();
                                if (lostResult != null)
                                    throw new AttemptTransactions.Failure(
                                            lostResult, new RuntimeException("lost result"));
                                journal.add("owner-return");
                                return result;
                            }

                            public void poison(Throwable failure) {}

                            public void requirePublicationClosed() {}
                        };
                var settings =
                        new DiscoveryEngine.Settings(
                                32,
                                pageSize,
                                softCap,
                                softCap,
                                Duration.ofMillis(10),
                                Duration.ofMillis(20));
                DiscoveryEngine discovery;
                if (family == ConsumableFamily.COMMAND
                        || family == ConsumableFamily.IDENTITY_REQUEST) {
                    var requests =
                            new BurnableReferences() {
                                public ConsumableFamily family() {
                                    return family;
                                }

                                public Optional<UUID> upperPending(RecoveryReadContext read) {
                                    journal.add("upper");
                                    if (sourceFailure) throw new IllegalStateException("source");
                                    return source.stream().max(SlotKeyNavigation::compare);
                                }

                                public List<UUID> pendingPage(
                                        Optional<UUID> after,
                                        UUID upper,
                                        int limit,
                                        RecoveryReadContext read) {
                                    journal.add("page:" + after);
                                    return source.stream()
                                            .filter(
                                                    k ->
                                                            after.isEmpty()
                                                                    || SlotKeyNavigation.compare(
                                                                                    k, after.get())
                                                                            > 0)
                                            .filter(k -> SlotKeyNavigation.compare(k, upper) <= 0)
                                            .limit(limit)
                                            .toList();
                                }

                                public boolean tryProtectPending(UUID key) {
                                    return true;
                                }
                            };
                    discovery =
                            new DiscoveryEngine(
                                    definition, owner, ownership, admissions, settings, requests);
                } else {
                    var events =
                            new NonBurnableReferences() {
                                public ConsumableFamily family() {
                                    return family;
                                }

                                public Window window(
                                        long after, int max, RecoveryReadContext read) {
                                    return new Window(
                                            after,
                                            after == 0 ? 1 : after,
                                            after == 0
                                                    ? List.of(
                                                            new Reference(
                                                                    1, source.getFirst(), "test"))
                                                    : List.of());
                                }
                            };
                    discovery =
                            new DiscoveryEngine(
                                    definition,
                                    owner,
                                    ownership,
                                    admissions,
                                    settings,
                                    owner,
                                    events,
                                    r -> true);
                }
                set(original.engine(), "ownership", ownership);
                bindings.add(
                        new V3ConsumptionWorker.Binding(
                                definition, original.engine(), discovery, List.of(0)));
            }
            set(base.worker, "bindings", List.copyOf(bindings));
            loop = new V3DiscoveryLoop(base.worker);
        }

        static void set(Object target, String name, Object value) throws Exception {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        void pass(int expectedTours) {
            protections = 0;
            loop.runOnePass();
            assertEquals(expectedTours, tours.get(), journal.toString());
            for (int i = 0; i < journal.size(); i++)
                if (journal.get(i).startsWith("metric:"))
                    assertEquals("owner-return", journal.get(i - 1));
        }

        public void close() throws Exception {
            base.close();
        }
    }

    @Test
    void i5_u11_discovery_tours() throws Exception {
        for (var family : List.of(ConsumableFamily.COMMAND, ConsumableFamily.IDENTITY_REQUEST)) {
            try (var f = new TourFixture(family, 2, 3, 1, 100)) {
                for (int expected : List.of(0, 0, 0, 0, 1, 2)) f.pass(expected);
                assertEquals(3, f.admitted.size());
                // Same keys, no new admission; the next complete traversal still counts.
                for (int expected : List.of(2, 2, 2, 2, 3, 4)) f.pass(expected);
                assertEquals(3, f.admitted.size());
            }
            try (var f = new TourFixture(family, 1, 0, 1, 100)) {
                f.pass(1);
            }
            try (var f = new TourFixture(family, 1, 3, 1, 100)) {
                f.pass(0);
                f.source.clear();
                f.pass(1);
            }
            try (var f = new TourFixture(family, 1, 3, 3, 1)) {
                f.full = true;
                f.pass(0);
                f.full = false;
                f.pass(0); // Partial prefix, capacity is reached before U.
                assertEquals(1, f.admitted.size());
                f.admitted.clear();
                f.pass(0);
                f.admitted.clear();
                f.pass(1);
            }
        }
    }

    @Test
    void i5_u12_discovery_negatives() throws Exception {
        for (int size : List.of(0, 1)) {
            try (var f = new TourFixture(ConsumableFamily.COMMAND, 1, size, 1, 100)) {
                f.noLease = true;
                f.pass(0);
                assertTrue(f.journal.isEmpty());
            }
            try (var f = new TourFixture(ConsumableFamily.COMMAND, 1, size, 1, 100)) {
                f.notOwner = true;
                f.pass(0);
            }
        }
        for (var resolution : AttemptTransactions.Resolution.values()) {
            try (var f = new TourFixture(ConsumableFamily.COMMAND, 1, 1, 1, 100)) {
                f.lostResult = resolution;
                assertThrows(AttemptTransactions.Failure.class, () -> f.loop.runOnePass());
                assertEquals(0, f.tours.get());
            }
        }
        for (String fault : List.of("source", "admission", "fence")) {
            try (var f = new TourFixture(ConsumableFamily.COMMAND, 1, 1, 1, 100)) {
                f.sourceFailure = fault.equals("source");
                f.admissionFailure = fault.equals("admission");
                f.fenceFailure = fault.equals("fence");
                assertThrows(IllegalStateException.class, () -> f.loop.runOnePass());
                assertEquals(0, f.tours.get());
            }
        }
        try (var f = new TourFixture(ConsumableFamily.POT_EVENT, 1, 1, 1, 100)) {
            f.pass(0);
            assertEquals(1, f.checkpoint);
            f.pass(0);
        }
        try (var f = new TourFixture(ConsumableFamily.COMMAND, 1, 3, 1, 100)) {
            f.observerFailure = true;
            f.pass(0);
            f.pass(0);
            f.pass(1);
            assertEquals(3, f.admitted.size());
        }
    }
}
