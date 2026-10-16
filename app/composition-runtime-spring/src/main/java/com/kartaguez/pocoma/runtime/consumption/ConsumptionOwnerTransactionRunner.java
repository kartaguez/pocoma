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
	public ConsumptionOwnerTransactionRunner(TransactionRunner delegate) { this.delegate = Objects.requireNonNull(delegate); }
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
			var outcome = completion[0] == TransactionSynchronization.STATUS_COMMITTED ? COMMITTED
					: completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK ? ROLLED_BACK : UNKNOWN;
			throw new ConsumptionTransactionResolutionException(outcome, returned[0] ? COMMIT : ROLLBACK, failure);
		}
		if (completion[0] != TransactionSynchronization.STATUS_COMMITTED)
			throw new ConsumptionTransactionResolutionException(UNKNOWN, COMMIT, new IllegalStateException("Commit unconfirmed"));
		return result;
	}
	@Override public void runAfterCommit(Runnable action) { delegate.runAfterCommit(action); }
}
