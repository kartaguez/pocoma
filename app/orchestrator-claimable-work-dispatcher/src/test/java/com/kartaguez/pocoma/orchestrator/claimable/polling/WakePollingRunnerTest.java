package com.kartaguez.pocoma.orchestrator.claimable.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

class WakePollingRunnerTest {

	@Test
	void startsAndRunsOnceImmediately() throws InterruptedException {
		CountDownLatch called = new CountDownLatch(1);
		WakePollingRunner<String, Integer> runner = runner(() -> {
			called.countDown();
			return 0;
		}, WorkWakeBus.noop(), false);

		runner.start();
		try {
			assertTrue(called.await(1, TimeUnit.SECONDS));
			assertTrue(runner.isRunning());
		}
		finally {
			runner.stop();
		}
	}

	@Test
	void drainsWhileRunOnceReportsProgress() throws InterruptedException {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch drained = new CountDownLatch(3);
		WakePollingRunner<String, Integer> runner = runner(() -> {
			drained.countDown();
			return calls.getAndIncrement() < 2 ? 1 : 0;
		}, WorkWakeBus.noop(), false);

		runner.start();
		try {
			assertTrue(drained.await(1, TimeUnit.SECONDS));
			assertEquals(3, calls.get());
		}
		finally {
			runner.stop();
		}
	}

	@Test
	void wakeSignalRunsAgainBeforeTimeout() throws InterruptedException {
		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch firstCall = new CountDownLatch(1);
		CountDownLatch secondCall = new CountDownLatch(1);
		WakePollingRunner<String, Integer> runner = runner(() -> {
			if (calls.incrementAndGet() == 1) {
				firstCall.countDown();
			}
			else {
				secondCall.countDown();
			}
			return 0;
		}, wakeBus, true);

		runner.start();
		try {
			assertTrue(firstCall.await(1, TimeUnit.SECONDS));
			wakeBus.publish("WORK_AVAILABLE", 1);
			assertTrue(secondCall.await(1, TimeUnit.SECONDS));
		}
		finally {
			runner.stop();
		}
	}

	@Test
	void continuesAfterRunOnceFailure() throws InterruptedException {
		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch successfulCall = new CountDownLatch(1);
		WakePollingRunner<String, Integer> runner = runner(() -> {
			if (calls.getAndIncrement() == 0) {
				throw new IllegalStateException("boom");
			}
			successfulCall.countDown();
			return 0;
		}, wakeBus, true);

		runner.start();
		try {
			wakeBus.publish("WORK_AVAILABLE", 1);
			assertTrue(successfulCall.await(1, TimeUnit.SECONDS));
		}
		finally {
			runner.stop();
		}
	}

	private static WakePollingRunner<String, Integer> runner(
			java.util.function.IntSupplier runOnce,
			WorkWakeBus<String, Integer> wakeBus,
			boolean wakeSignalsEnabled) {
		return new WakePollingRunner<>(
				runOnce,
				processed -> processed > 0,
				new WakePollingRunnerSettings(
						true,
						"test-runner",
						Duration.ofSeconds(30),
						wakeSignalsEnabled),
				wakeBus,
				Set.of("WORK_AVAILABLE"),
				key -> key % 2 == 1);
	}
}
