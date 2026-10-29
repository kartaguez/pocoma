package com.kartaguez.pocoma.engine.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

class ConsumptionFailureClassificationTest {
    final ConsumptionDefinition definition =
            new ConsumptionDefinition(
                    new ConsumptionId(new UUID(0, 1)),
                    ConsumableFamily.COMMAND,
                    ConsumptionDefinition.Authority.WRITE);
    final AtomicInteger cessions = new AtomicInteger();
    final WorkerCapacity capacity = new WorkerCapacity(1, Map.of(definition.id(), 1));
    final AtomicReference<Resolution> resolution = new AtomicReference<>(Resolution.ROLLED_BACK);
    final AtomicReference<Throwable> cause =
            new AtomicReference<>(new NullPointerException("real fault"));
    final AtomicReference<ConsumptionSlotStore.Inspection> inspection =
            new AtomicReference<>(ConsumptionSlotStore.Inspection.PRESENT_UNLOCKED);
    final AtomicReference<RecoveryObservation> observation =
            new AtomicReference<>(RecoveryObservation.NOT_COMPLETED);
    final AtomicInteger prepares = new AtomicInteger();
    final AttemptTransactions tx =
            new AttemptTransactions() {
                public <T> T read(Function<RecoveryReadContext, T> f) {
                    return f.apply(() -> {});
                }

                public <T> T write(Supplier<T> f) {
                    return f.get();
                }

                public void poison(Throwable c) {}

                public void requirePublicationClosed() {}
            };

    ConsumptionEngine engine(Runnable callback) {
        return engine(
                callback,
                c ->
                        c instanceof NullPointerException
                                ? FailureKind.TECHNICAL
                                : FailureKind.NON_COUNTABLE);
    }

    ConsumptionEngine engine(Runnable callback, AttemptFailureClassifier classifier) {
        var slots =
                (ConsumptionSlotStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ConsumptionSlotStore.class},
                                (p, m, args) ->
                                        switch (m.getName()) {
                                            case "cede" -> {
                                                cessions.incrementAndGet();
                                                yield Optional.empty();
                                            }
                                            case "inspectResolved" ->
                                                    ((List<ClaimedSlot>) args[0])
                                                            .stream()
                                                                    .map(
                                                                            c ->
                                                                                    new ConsumptionSlotStore
                                                                                            .ClaimInspection(
                                                                                            c,
                                                                                            inspection
                                                                                                    .get()))
                                                                    .toList();
                                            case "observe" ->
                                                    observation.get()
                                                                    == RecoveryObservation.COMPLETED
                                                            ? ConsumptionSlotStore.SlotObservation
                                                                    .ABSENT
                                                            : ConsumptionSlotStore.SlotObservation
                                                                    .PRESENT;
                                            default -> throw new AssertionError(m.getName());
                                        });
        var ownership =
                (PartitionOwnershipStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {PartitionOwnershipStore.class},
                                (p, m, args) -> null);
        var participant =
                new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id) {
                        prepares.incrementAndGet();
                        callback.run();
                        throw new Failure(resolution.get(), cause.get());
                    }

                    public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                        return observation.get();
                    }
                };
        return new ConsumptionEngine(
                definition,
                tx,
                slots,
                participant,
                ownership,
                LifecycleSettings.pilot(),
                capacity,
                classifier);
    }

    ConsumptionEngine.AcquiredClaim handle(ConsumptionEngine engine) {
        return new ConsumptionEngine.AcquiredClaim(
                engine,
                new ClaimedSlot(definition.id(), new UUID(0, 2), new UUID(0, 3)),
                capacity.reserve(definition.id(), 1).getFirst());
    }

    @Test
    void classifierExceptionAfterConfirmedRollbackReturnsCapacity() {
        var fault = new IllegalStateException("classifier failed");
        var e =
                engine(
                        () -> {},
                        c -> {
                            throw fault;
                        });
        var h = handle(e);
        assertSame(fault, assertThrows(IllegalStateException.class, () -> e.execute(h)));
        assertEquals(0, cessions.get());
        assertEquals(0, capacity.snapshot().worker());
        assertEquals(0, e.settlePending());
        assertThrows(IllegalArgumentException.class, () -> e.execute(h));
        e.abandon(handle(e));
        assertEquals(0, capacity.snapshot().worker());
    }

    @Test
    void nullClassificationAfterConfirmedRollbackReturnsCapacity() {
        var e = engine(() -> {}, c -> null);
        assertThrows(NullPointerException.class, () -> e.execute(handle(e)));
        assertEquals(0, cessions.get());
        assertEquals(0, capacity.snapshot().worker());
        assertEquals(0, e.settlePending());
    }

    @Test
    void countableRequiresKnownCauseAndRollback() {
        var e = engine(() -> {});
        var h = handle(e);
        assertEquals(FailureKind.TECHNICAL, e.execute(h).failureKind());
        assertEquals(1, cessions.get());
        cause.set(new CancellationException());
        e.execute(handle(e));
        assertEquals(1, cessions.get());
        resolution.set(Resolution.UNKNOWN);
        cause.set(new NullPointerException());
        e.execute(handle(e));
        assertEquals(1, cessions.get());
        assertEquals(1, capacity.snapshot().worker());
        assertEquals(0, e.settlePending());
        assertEquals(
                1,
                capacity.snapshot().worker(),
                "an unlocked slot cannot resolve UNKNOWN read-only preparation");
    }

    @Test
    void committedPreparationAckCanBeInspected() {
        resolution.set(Resolution.COMMITTED);
        var e = engine(() -> {});
        var h = handle(e);
        assertEquals(Resolution.UNKNOWN, e.execute(h).resolution());
        assertEquals(1, e.settlePending());
        assertEquals(0, capacity.snapshot().worker());
        assertEquals(0, cessions.get());
        assertThrows(IllegalArgumentException.class, () -> e.execute(h));
    }

    @Test
    void rawPreparationExitCannotBeSettledFromSlot() {
        var e =
                engine(
                        () -> {
                            throw new AssertionError("raw owner/callback exit");
                        });
        var h = handle(e);
        assertThrows(AssertionError.class, () -> e.execute(h));
        assertEquals(0, e.settlePending());
        assertEquals(1, capacity.snapshot().worker());
        assertEquals(0, cessions.get());
        assertThrows(IllegalArgumentException.class, () -> e.execute(h));
    }

    @Test
    void lostUnknownCancellationAndConfigNeverCount() {
        for (var fault :
                List.of(
                        new CancellationException(),
                        new IllegalArgumentException("configuration"),
                        new RuntimeException("rollback wrapper"))) {
            cause.set(fault);
            var e = engine(() -> {});
            e.execute(handle(e));
            assertEquals(0, cessions.get());
        }
        resolution.set(Resolution.UNKNOWN);
        var e = engine(() -> {});
        e.execute(handle(e));
        inspection.set(ConsumptionSlotStore.Inspection.LOCKED);
        assertEquals(0, e.settlePending());
        assertEquals(1, capacity.snapshot().worker());
    }

    @Test
    void oneShotAndCancellation() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        resolution.set(Resolution.UNKNOWN);
        var e =
                engine(
                        () -> {
                            entered.countDown();
                            boolean done = false;
                            while (!done)
                                try {
                                    finish.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                }
                        });
        var h = handle(e);
        // The fake SQL ports cannot grant a claim: a full ticket budget must return before them.
        var noPlace =
                e.acquire(
                        new PartitionLease(definition.id(), 0, new UUID(0, 9), Instant.now()),
                        List.of(new UUID(0, 10)));
        assertEquals(0, noPlace.examinedCount());
        assertTrue(noPlace.claims().isEmpty());
        var exited = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var f =
                    threads.submit(
                            () -> {
                                try {
                                    return e.execute(h);
                                } finally {
                                    exited.countDown();
                                }
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.cancel(true);
            assertEquals(0, e.settlePending());
            assertEquals(1, capacity.snapshot().worker());
            assertThrows(IllegalArgumentException.class, () -> e.execute(h));
            finish.countDown();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        } finally {
            finish.countDown();
        }
        assertEquals(0, e.settlePending());
        assertEquals(1, prepares.get());
        assertEquals(1, capacity.snapshot().worker());
        assertEquals(0, e.settlePending());
    }

    @Test
    void unusedConfirmedClaimCanBeAbandoned() {
        var e = engine(() -> {});
        var unused = handle(e);
        e.abandon(unused);
        assertEquals(0, capacity.snapshot().worker());
        assertThrows(IllegalArgumentException.class, () -> e.execute(unused));
    }
}
