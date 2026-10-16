package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner;

/** Boundary fault injection only; PostgreSQL atomicity is tested separately. */
class ConsumptionOwnerTransactionRunnerTest {
    @Test
    void rollbackFailureWithUnconfirmedCompletionCannotBecomeLostClaim() {
        var lost = new LostClaimException(UUID.randomUUID(), new ClaimId(UUID.randomUUID()));
        var rollbackFailure = new IllegalStateException("injected rollback failure");
        var owner = new ConsumptionOwnerTransactionRunner(new UnconfirmedRunner(rollbackFailure));
        var failure = assertThrows(ConsumptionTransactionResolutionException.class,
                () -> owner.runInTransaction(() -> { throw lost; }));
        assertEquals(ConsumptionTransactionResolutionException.Outcome.UNKNOWN, failure.outcome());
        assertEquals(ConsumptionTransactionResolutionException.Phase.ROLLBACK, failure.phase());
        assertSame(rollbackFailure, failure.getCause());
        assertSame(lost, rollbackFailure.getSuppressed()[0]);
    }

    @Test
    void normalActionReturnWithoutConfirmedCommitCannotBecomeApplied() {
        var owner = new ConsumptionOwnerTransactionRunner(new UnconfirmedRunner(null));
        var failure = assertThrows(ConsumptionTransactionResolutionException.class,
                () -> owner.runInTransaction(() -> "APPLIED"));
        assertEquals(ConsumptionTransactionResolutionException.Outcome.UNKNOWN, failure.outcome());
        assertEquals(ConsumptionTransactionResolutionException.Phase.COMMIT, failure.phase());
    }

    private record UnconfirmedRunner(RuntimeException rollbackFailure) implements TransactionRunner {
        @Override public <T> T runInTransaction(Supplier<T> action) {
            TransactionSynchronizationManager.initSynchronization();
            try { return action.get(); }
            catch (RuntimeException original) {
                rollbackFailure.addSuppressed(original);
                throw rollbackFailure;
            } finally {
                // Simulates a failed boundary which cannot confirm afterCompletion.
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
        @Override public void runAfterCommit(Runnable action) { throw new UnsupportedOperationException(); }
    }
}
