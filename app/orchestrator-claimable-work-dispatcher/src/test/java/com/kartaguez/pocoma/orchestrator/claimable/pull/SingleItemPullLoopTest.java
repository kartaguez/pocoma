package com.kartaguez.pocoma.orchestrator.claimable.pull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

class SingleItemPullLoopTest {

	private static final Duration LONG_POLL = Duration.ofSeconds(30);

	@Test
	void directRunCallsTheIterationOnceAndPreservesItsResult() {
		AtomicInteger calls = new AtomicInteger();
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			calls.incrementAndGet();
			return true;
		}, false, WorkWakeBus.noop(), false);

		assertTrue(loop.runOnce());
		assertEquals(1, calls.get());
	}

	@Test
	void drainsSuccessfulIterationsSequentiallyThenWaits() throws InterruptedException {
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maximumInFlight = new AtomicInteger();
		CountDownLatch drained = new CountDownLatch(3);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			int active = inFlight.incrementAndGet();
			maximumInFlight.accumulateAndGet(active, Math::max);
			try {
				drained.countDown();
				return calls.getAndIncrement() < 2;
			}
			finally {
				inFlight.decrementAndGet();
			}
		}, true, WorkWakeBus.noop(), false);

		loop.start();
		try {
			assertTrue(drained.await(2, TimeUnit.SECONDS));
			assertEquals(3, calls.get());
			assertEquals(1, maximumInFlight.get());
		}
		finally {
			loop.stop();
		}
	}

	@Test
	void serializesConcurrentDirectInvocations() throws Exception {
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maximumInFlight = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondSubmitted = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			int active = inFlight.incrementAndGet();
			maximumInFlight.accumulateAndGet(active, Math::max);
			try {
				if (first.compareAndSet(true, false)) {
					firstEntered.countDown();
					await(releaseFirst);
				}
				return false;
			}
			finally {
				inFlight.decrementAndGet();
			}
		}, false, WorkWakeBus.noop(), false);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> firstRun = executor.submit(loop::runOnce);
			assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
			Future<Boolean> secondRun = executor.submit(() -> {
				secondSubmitted.countDown();
				return loop.runOnce();
			});
			assertTrue(secondSubmitted.await(2, TimeUnit.SECONDS));
			releaseFirst.countDown();
			assertFalse(firstRun.get(2, TimeUnit.SECONDS));
			assertFalse(secondRun.get(2, TimeUnit.SECONDS));
			assertEquals(1, maximumInFlight.get());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void disabledLoopDoesNotStartAndLifecycleIsIdempotent() {
		AtomicInteger calls = new AtomicInteger();
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			calls.incrementAndGet();
			return false;
		}, false, WorkWakeBus.noop(), false);

		loop.start();
		loop.start();
		assertFalse(loop.isRunning());
		assertEquals(0, calls.get());
		loop.stop();
		loop.stop();
	}

	@Test
	void startIsIdempotentAndStopInterruptsTheWait() throws InterruptedException {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch called = new CountDownLatch(1);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			calls.incrementAndGet();
			called.countDown();
			return false;
		}, true, WorkWakeBus.noop(), false);

		loop.start();
		loop.start();
		assertTrue(called.await(2, TimeUnit.SECONDS));
		loop.stop();
		loop.stop();
		assertFalse(loop.isRunning());
		assertEquals(1, calls.get());
	}

	@Test
	void matchingWakeSignalRunsAgainBeforeThePollingTimeout() throws InterruptedException {
		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch firstCall = new CountDownLatch(1);
		CountDownLatch secondCall = new CountDownLatch(1);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			if (calls.incrementAndGet() == 1) {
				firstCall.countDown();
			}
			else {
				secondCall.countDown();
			}
			return false;
		}, true, wakeBus, true);

		loop.start();
		try {
			assertTrue(firstCall.await(2, TimeUnit.SECONDS));
			wakeBus.publish("WORK_AVAILABLE", 1);
			assertTrue(secondCall.await(2, TimeUnit.SECONDS));
			assertEquals(2, calls.get());
		}
		finally {
			loop.stop();
		}
	}

	@Test
	void wakeFilteringIsDelegatedWithoutChangingSignalsOrPredicate() throws InterruptedException {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		CountDownLatch called = new CountDownLatch(1);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			called.countDown();
			return false;
		}, true, wakeBus, true);

		loop.start();
		try {
			assertTrue(called.await(2, TimeUnit.SECONDS));
			assertEquals(Set.of("WORK_AVAILABLE"), wakeBus.signals);
			assertFalse(wakeBus.keyPredicate.test(2));
			assertTrue(wakeBus.keyPredicate.test(1));
		}
		finally {
			loop.stop();
		}
	}

	@Test
	void disabledWakeSignalsDoNotSubscribe() throws InterruptedException {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		CountDownLatch called = new CountDownLatch(1);
		SingleItemPullLoop<String, Integer> loop = loop(() -> {
			called.countDown();
			return false;
		}, true, wakeBus, false);

		loop.start();
		try {
			assertTrue(called.await(2, TimeUnit.SECONDS));
			assertEquals(0, wakeBus.subscriptions.get());
		}
		finally {
			loop.stop();
		}
	}

	@Test
	void directFailureIsUnchangedAndAutonomousLoopCanResume() throws InterruptedException {
		RuntimeException failure = new RuntimeException("boom");
		SingleItemPullLoop<String, Integer> direct = loop(() -> {
			throw failure;
		}, false, WorkWakeBus.noop(), false);
		assertSame(failure, assertThrows(RuntimeException.class, direct::runOnce));

		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch resumed = new CountDownLatch(1);
		SingleItemPullLoop<String, Integer> autonomous = loop(() -> {
			if (calls.getAndIncrement() == 0) {
				throw failure;
			}
			resumed.countDown();
			return false;
		}, true, wakeBus, true);

		autonomous.start();
		try {
			wakeBus.publish("WORK_AVAILABLE", 1);
			assertTrue(resumed.await(2, TimeUnit.SECONDS));
		}
		finally {
			autonomous.stop();
		}
	}

	private static SingleItemPullLoop<String, Integer> loop(
			PullIteration iteration,
			boolean enabled,
			WorkWakeBus<String, Integer> wakeBus,
			boolean wakeSignalsEnabled) {
		return new SingleItemPullLoop<>(
				iteration,
				new SingleItemPullLoopSettings(enabled, "test-worker", LONG_POLL, wakeSignalsEnabled),
				wakeBus,
				Set.of("WORK_AVAILABLE"),
				key -> key % 2 == 1);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) {
				throw new AssertionError("latch timed out");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static final class RecordingWakeBus implements WorkWakeBus<String, Integer> {
		private final AtomicInteger subscriptions = new AtomicInteger();
		private Set<String> signals = Set.of();
		private java.util.function.Predicate<Integer> keyPredicate = ignored -> false;

		@Override
		public void publish(com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeEvent<String, Integer> event) {
		}

		@Override
		public com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeSubscription subscribe(
				Set<String> signals,
				java.util.function.Predicate<Integer> keyPredicate,
				Runnable listener) {
			this.subscriptions.incrementAndGet();
			this.signals = Set.copyOf(signals);
			this.keyPredicate = keyPredicate;
			return () -> { };
		}
	}
}
