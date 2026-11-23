package com.kartaguez.pocoma.orchestrator.consumption;

import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Pure, monotone invocation guard for preparation and recovery read snapshots. */
final class AttemptPreparationReads implements PreparationReads, AttemptTransactions.Guard {
    private final AttemptTransactions sourceTransactions;
    private final AttemptTransactions effectsTransactions;
    private final Thread thread = Thread.currentThread();
    private final AtomicReference<Throwable> first = new AtomicReference<>();
    private boolean open = true;
    private boolean active;
    private volatile boolean uncertain;
    private volatile Resolution failedResolution;
    private int entered;

    AttemptPreparationReads(
            AttemptTransactions sourceTransactions, AttemptTransactions effectsTransactions) {
        this.sourceTransactions = sourceTransactions;
        this.effectsTransactions = effectsTransactions;
    }

    @Override
    public <T> T source(Function<RecoveryReadContext, T> action) {
        return scope(sourceTransactions, action);
    }

    @Override
    public <T> T effects(Function<RecoveryReadContext, T> action) {
        return scope(effectsTransactions, action);
    }

    @Override
    public void invalidate(Throwable cause) {
        first.compareAndSet(null, Objects.requireNonNull(cause));
    }

    Throwable cause() {
        return first.get();
    }

    Resolution failedResolution() {
        return failedResolution;
    }

    @Override
    public void requireValid() {
        if (Thread.currentThread().isInterrupted()) {
            invalidate(new PreparationReads.Invalid("Preparation interrupted", null));
        }
        var failure = first.get();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Preparation failed", failure);
    }

    private <T> T scope(AttemptTransactions owner, Function<RecoveryReadContext, T> action) {
        enterScope(action);
        try {
            T result = owner.read(action, this);
            // Physical success preceded this barrier; a late poison cannot undo that success.
            requireValid();
            return result;
        } catch (Failure failure) {
            failedResolution = failure.resolution();
            if (failure.resolution() == Resolution.UNKNOWN) uncertain = true;
            invalidate(failure.getCause());
            throw failure;
        } catch (RuntimeException | Error failure) {
            uncertain = true;
            invalidate(failure);
            throw failure;
        } finally {
            synchronized (this) {
                active = false;
            }
        }
    }

    private synchronized void enterScope(Function<RecoveryReadContext, ?> action) {
        if (Thread.currentThread() != thread || !open || active) {
            var fault =
                    new PreparationReads.Invalid(
                            "Preparation scope expired, nested or foreign", null);
            invalidate(fault);
            throw fault;
        }
        try {
            requireValid();
            Objects.requireNonNull(action);
        } catch (RuntimeException | Error failure) {
            invalidate(failure);
            throw failure;
        }
        active = true;
        entered++;
    }

    synchronized void expire() {
        open = false;
    }

    boolean physicallyUnresolved() {
        return uncertain;
    }
}
