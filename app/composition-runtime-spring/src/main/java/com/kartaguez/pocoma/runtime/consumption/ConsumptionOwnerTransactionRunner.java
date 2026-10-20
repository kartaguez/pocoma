package com.kartaguez.pocoma.runtime.consumption;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import static com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.*;
import static com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Phase.*;
import java.util.Objects;

/** Local owner guard; stores and internal participants keep their REQUIRED runner. */
public final class ConsumptionOwnerTransactionRunner implements TransactionRunner {
	private final TransactionRunner delegate;
	private final boolean preserveActionFailure;
	public ConsumptionOwnerTransactionRunner(TransactionRunner delegate) { this(delegate, false); }
	/** COMMAND opts in to keeping its technical cause when transaction resolution replaces it. */
	public ConsumptionOwnerTransactionRunner(TransactionRunner delegate, boolean preserveActionFailure) {
		this.delegate = Objects.requireNonNull(delegate);
		this.preserveActionFailure = preserveActionFailure;
	}
	public static void requireNoAmbientTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()
				|| TransactionSynchronizationManager.isSynchronizationActive())
			throw new IllegalStateException("Consumption owner requires no ambient transaction or synchronization");
	}
	@Override public <T> T runInTransaction(Supplier<T> action) {
		requireNoAmbientTransaction();
		var completion = new int[] { TransactionSynchronization.STATUS_UNKNOWN };
		var returned = new boolean[] { false };
		var actionFailure = new Throwable[1];
		T result;
		try {
			result = delegate.runInTransaction(() -> {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override public void afterCompletion(int status) { completion[0] = status; }
				});
				try {
					T value = action.get(); returned[0] = true; return value;
				} catch (RuntimeException | Error failure) { actionFailure[0] = failure; throw failure; }
			});
		} catch (RuntimeException failure) {
			if (completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK && actionFailure[0] == failure) throw failure;
			// A transaction manager can replace the application error during resolution.
			// Preserve it on the cause classified only after confirmed rollback.
			if (preserveActionFailure && actionFailure[0] != null && actionFailure[0] != failure
					&& java.util.Arrays.stream(failure.getSuppressed()).noneMatch(value -> value == actionFailure[0]))
				failure.addSuppressed(actionFailure[0]);
			var outcome = failure instanceof com.kartaguez.pocoma.port.transaction.TransactionCommitUnknownException ? UNKNOWN
                    : completion[0] == TransactionSynchronization.STATUS_COMMITTED ? COMMITTED
					: completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK ? ROLLED_BACK : UNKNOWN;
			throw new ConsumptionTransactionResolutionException(outcome, returned[0] ? COMMIT : ROLLBACK, failure);
		}
		if (completion[0] != TransactionSynchronization.STATUS_COMMITTED)
			throw new ConsumptionTransactionResolutionException(UNKNOWN, COMMIT, new IllegalStateException("Commit unconfirmed"));
		return result;
	}
	@Override public void runAfterCommit(Runnable action) { delegate.runAfterCommit(action); }
}
