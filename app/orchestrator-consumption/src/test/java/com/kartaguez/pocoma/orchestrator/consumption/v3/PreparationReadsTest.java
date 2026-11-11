package com.kartaguez.pocoma.orchestrator.consumption.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Protocol tests; physical resolution is separately qualified against PostgreSQL. */
class PreparationReadsTest {
    final ConsumptionDefinition definition =
            new ConsumptionDefinition(
                    new ConsumptionId(UUID.randomUUID()),
                    ConsumableFamily.COMMAND,
                    ConsumptionDefinition.Authority.WRITE);
    final WorkerCapacity capacity = new WorkerCapacity(1, Map.of(definition.id(), 1));
    final AtomicInteger entered = new AtomicInteger(),
            finalized = new AtomicInteger(),
            recoveries = new AtomicInteger();
    Resolution failed;
    Error raw;
    RuntimeException recoveryFailure;
    final List<String> timeline = new ArrayList<>();
    final AttemptTransactions owner =
            new AttemptTransactions() {
                public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) {
                    guard.requireValid();
                    entered.incrementAndGet();
                    timeline.add("begin");
                    try {
                        T value = action.apply(guard::requireValid);
                        if (raw != null) throw raw;
                        if (failed != null)
                            throw new Failure(
                                    failed, new IllegalStateException("lost read result"));
                        guard.requireValid();
                        return value;
                    } catch (Failure failure) {
                        throw failure;
                    } catch (RuntimeException failure) {
                        guard.invalidate(failure);
                        throw new Failure(Resolution.ROLLED_BACK, failure);
                    } finally {
                        timeline.add("resolved");
                    }
                }

                public <T> T write(Supplier<T> action, Guard guard) {
                    guard.requireValid();
                    return action.get();
                }

                public void poison(Throwable fault) {
                    throw new AssertionError("unused");
                }

                public void requirePublicationClosed() {}
            };

    ConsumptionEngine engine(
            Function<PreparationReads, Optional<ConsumptionParticipant.Prepared>> prepare) {
        var participant =
                new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                        return prepare.apply(reads);
                    }

                    public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                        recoveries.incrementAndGet();
                        if (recoveryFailure != null) throw recoveryFailure;
                        return context -> EffectEvidence.MISSING;
                    }
                };
        var slots =
                (ConsumptionSlotStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {ConsumptionSlotStore.class},
                                (p, m, a) ->
                                        switch (m.getName()) {
                                            case "protectFinal" ->
                                                    ConsumptionSlotStore.Protection.VALID;
                                            case "deleteProtected" -> null;
                                            case "observe" ->
                                                    ConsumptionSlotStore.SlotObservation.PRESENT;
                                            case "inspectResolved" ->
                                                    List.of(
                                                            new ConsumptionSlotStore
                                                                    .ClaimInspection(
                                                                    ((List<ClaimedSlot>) a[0])
                                                                            .getFirst(),
                                                                    ConsumptionSlotStore.Inspection
                                                                            .PRESENT_UNLOCKED));
                                            default -> throw new AssertionError(m.getName());
                                        });
        var ownership =
                (PartitionOwnershipStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {PartitionOwnershipStore.class},
                                (p, m, a) -> null);
        return new ConsumptionEngine(
                definition,
                owner,
                slots,
                participant,
                ownership,
                LifecycleSettings.pilot(),
                capacity,
                cause -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
    }

    Optional<ConsumptionParticipant.Prepared> prepared() {
        return Optional.of(
                new ConsumptionParticipant.Prepared() {
                    public void finalizeIn(SlotCompletion completion) {
                        finalized.incrementAndGet();
                        completion.complete();
                    }

                    public void requireCompletionReady() {}
                });
    }

    ConsumptionEngine.AcquiredClaim handle(ConsumptionEngine e) {
        return new ConsumptionEngine.AcquiredClaim(
                e,
                new ClaimedSlot(definition.id(), UUID.randomUUID(), UUID.randomUUID()),
                capacity.reserve(definition.id(), 1).getFirst());
    }

    @Test
    void consecutiveScopesResolveBeforeNextBegin() {
        var e =
                engine(
                        reads -> {
                            reads.source(c -> 1);
                            reads.effects(c -> 2);
                            reads.source(c -> 3);
                            return prepared();
                        });
        assertEquals(ConsumptionProtocol.Status.COMMITTED, e.execute(handle(e)).status());
        assertEquals(
                List.of("begin", "resolved", "begin", "resolved", "begin", "resolved"), timeline);
        assertEquals(1, finalized.get());
        assertEquals(0, capacity.snapshot().worker());
    }

    @Test
    void caughtNestedScopeStillPoisonsAndPreventsFinalization() {
        var e =
                engine(
                        reads -> {
                            try {
                                reads.source(
                                        c -> {
                                            assertThrows(
                                                    PreparationReads.Invalid.class,
                                                    () -> reads.effects(x -> 0));
                                            return 1;
                                        });
                            } catch (Failure expected) {
                            }
                            assertThrows(
                                    PreparationReads.Invalid.class, () -> reads.effects(c -> 2));
                            return prepared();
                        });
        var result = e.execute(handle(e));
        assertEquals(Resolution.ROLLED_BACK, result.resolution());
        assertInstanceOf(PreparationReads.Invalid.class, result.cause());
        assertEquals(1, entered.get());
        assertEquals(0, finalized.get());
    }

    @Test
    void foreignThreadInvalidatesWithoutOpeningAnOwner() throws Exception {
        var e =
                engine(
                        reads -> {
                            var failure = new AtomicReference<Throwable>();
                            var t =
                                    new Thread(
                                            () -> {
                                                try {
                                                    reads.source(c -> 1);
                                                } catch (Throwable fault) {
                                                    failure.set(fault);
                                                }
                                            });
                            t.start();
                            try {
                                t.join();
                            } catch (InterruptedException fault) {
                                throw new AssertionError(fault);
                            }
                            assertInstanceOf(PreparationReads.Invalid.class, failure.get());
                            return prepared();
                        });
        assertEquals(Resolution.ROLLED_BACK, e.execute(handle(e)).resolution());
        assertEquals(0, entered.get());
        assertEquals(0, finalized.get());
    }

    @Test
    void expiredCapabilityCannotReopenOrUndoPhysicalCommit() {
        var retained = new AtomicReference<PreparationReads>();
        var e =
                engine(
                        reads -> {
                            retained.set(reads);
                            reads.source(c -> 1);
                            return prepared();
                        });
        var result = e.execute(handle(e));
        assertEquals(Resolution.COMMITTED, result.resolution());
        assertThrows(PreparationReads.Invalid.class, () -> retained.get().source(c -> 1));
        assertEquals(Resolution.COMMITTED, result.resolution());
        assertEquals(1, entered.get());
    }

    @Test
    void unknownReadCaughtByParticipantRetainsCapacityAndForbidsRecovery() {
        failed = Resolution.UNKNOWN;
        var e =
                engine(
                        reads -> {
                            assertThrows(Failure.class, () -> reads.source(c -> 1));
                            assertThrows(IllegalStateException.class, () -> reads.effects(c -> 1));
                            return prepared();
                        });
        var h = handle(e);
        var result = e.execute(h);
        assertEquals(Resolution.UNKNOWN, result.resolution());
        assertTrue(e.retainsCapacity(h));
        assertEquals(0, e.settlePending());
        assertEquals(0, finalized.get());
        assertEquals(0, recoveries.get());
    }

    @Test
    void committedReadWithLostResultDoesNotCommitBusiness() {
        failed = Resolution.COMMITTED;
        var e =
                engine(
                        reads -> {
                            reads.source(c -> 1);
                            return prepared();
                        });
        var h = handle(e);
        var result = e.execute(h);
        assertEquals(Resolution.UNKNOWN, result.resolution());
        assertEquals(0, finalized.get());
        assertEquals(1, recoveries.get());
        assertEquals(1, e.settlePending());
        assertFalse(e.retainsCapacity(h));
    }

    @Test
    void rolledBackReadPreservesOriginalCause() {
        var original = new IllegalArgumentException("decode");
        var e =
                engine(
                        reads -> {
                            reads.source(
                                    c -> {
                                        throw original;
                                    });
                            return prepared();
                        });
        var result = e.execute(handle(e));
        assertSame(original, result.cause());
        assertEquals(Resolution.ROLLED_BACK, result.resolution());
    }

    @Test
    void unqualifiedOwnerErrorIsConservative() {
        raw = new AssertionError("unqualified physical exit");
        var e =
                engine(
                        reads -> {
                            reads.source(c -> 1);
                            return prepared();
                        });
        var h = handle(e);
        assertSame(raw, assertThrows(AssertionError.class, () -> e.execute(h)));
        assertTrue(e.retainsCapacity(h));
        assertEquals(0, recoveries.get());
        assertEquals(0, e.settlePending());
    }

    @Test
    void pureFailureOutsideResolvedScopesDoesNotInventUnknownRead() {
        var original = new IllegalStateException("detached calculation");
        var e =
                engine(
                        reads -> {
                            reads.source(c -> 1);
                            throw original;
                        });
        var h = handle(e);
        var result = e.execute(h);
        assertSame(original, result.cause());
        assertEquals(Resolution.ROLLED_BACK, result.resolution());
        assertFalse(e.retainsCapacity(h));
        assertEquals(0, finalized.get());
    }

    @Test
    void pureCaughtJavaExceptionWithoutGuardInteractionIsUnobservable() {
        var e =
                engine(
                        reads -> {
                            try {
                                throw new IllegalStateException("pure");
                            } catch (IllegalStateException ignored) {
                            }
                            return prepared();
                        });
        assertEquals(Resolution.COMMITTED, e.execute(handle(e)).resolution());
        assertEquals(1, finalized.get());
    }

    @Test
    void caughtNullActionInvalidatesBeforeBegin() {
        var e =
                engine(
                        reads -> {
                            assertThrows(NullPointerException.class, () -> reads.source(null));
                            return prepared();
                        });
        assertEquals(Resolution.ROLLED_BACK, e.execute(handle(e)).resolution());
        assertEquals(0, entered.get());
        assertEquals(0, finalized.get());
    }

    @Test
    void pureRecoveryPreparationFailureDoesNotInventAPhysicalUnknown() {
        recoveryFailure = new IllegalStateException("detached comparison setup");
        var e = engine(reads -> prepared());
        var claim = new ClaimedSlot(definition.id(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(RecoveryObservation.UNAVAILABLE, e.observe(claim));
        assertFalse(e.hasUnresolvedRecovery());
        assertEquals(RecoveryObservation.UNAVAILABLE, e.observe(claim));
        assertEquals(2, recoveries.get());
        assertEquals(0, entered.get());
    }
}
