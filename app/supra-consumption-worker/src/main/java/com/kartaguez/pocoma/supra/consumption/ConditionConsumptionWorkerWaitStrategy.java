package com.kartaguez.pocoma.supra.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ConditionConsumptionWorkerWaitStrategy implements ConsumptionWorkerWaitStrategy {
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition signalled = lock.newCondition();

	@Override
	public void await(Duration duration) throws InterruptedException {
		requireNonNull(duration, "duration must not be null");
		if (duration.isZero() || duration.isNegative()) return;
		lock.lockInterruptibly();
		try { signalled.awaitNanos(duration.toNanos()); }
		finally { lock.unlock(); }
	}

	@Override
	public void signal() {
		lock.lock();
		try { signalled.signalAll(); }
		finally { lock.unlock(); }
	}
}
