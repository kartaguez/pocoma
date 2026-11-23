package com.kartaguez.pocoma.supra.polling.consumption.wait;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ConditionConsumptionWaiter implements ConsumptionWaiter {
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition signalled = lock.newCondition();
	private boolean pendingSignal;

	@Override
	public void await(Duration duration) throws InterruptedException {
		requireNonNull(duration, "duration must not be null");
		if (duration.isZero() || duration.isNegative()) {
			lock.lockInterruptibly();
			try { pendingSignal = false; }
			finally { lock.unlock(); }
			return;
		}
		lock.lockInterruptibly();
		try {
			if (pendingSignal) {
				pendingSignal = false;
				return;
			}
			signalled.awaitNanos(duration.toNanos());
			pendingSignal = false;
		}
		finally { lock.unlock(); }
	}

	@Override
	public void signal() {
		lock.lock();
		try {
			pendingSignal = true;
			signalled.signalAll();
		}
		finally { lock.unlock(); }
	}
}
