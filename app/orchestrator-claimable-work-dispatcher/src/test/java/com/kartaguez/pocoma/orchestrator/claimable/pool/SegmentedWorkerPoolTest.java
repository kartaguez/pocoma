package com.kartaguez.pocoma.orchestrator.claimable.pool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;

class SegmentedWorkerPoolTest {

	@Test
	void heartbeatsWhileWorkIsProcessing() throws InterruptedException {
		RecordingLifecycle lifecycle = new RecordingLifecycle();
		CountDownLatch handlerCanReturn = new CountDownLatch(1);
		SegmentedWorkerPool<TestWork, Integer> pool = pool(
				lifecycle,
				work -> await(handlerCanReturn));

		pool.start();
		try {
			assertTrue(pool.trySubmit(new ClaimedWork<>(new TestWork(1))));
			assertTrue(lifecycle.heartbeatCalled.await(1, TimeUnit.SECONDS));

			handlerCanReturn.countDown();

			assertTrue(lifecycle.doneCalled.await(1, TimeUnit.SECONDS));
			assertFalse(lifecycle.ownershipLost.get());
		}
		finally {
			pool.stop();
		}
	}

	@Test
	void doesNotMarkDoneWhenHeartbeatLosesOwnership() throws InterruptedException {
		RecordingLifecycle lifecycle = new RecordingLifecycle();
		lifecycle.heartbeatAccepted.set(false);
		SegmentedWorkerPool<TestWork, Integer> pool = pool(
				lifecycle,
				work -> {
					while (!Thread.currentThread().isInterrupted()) {
						sleep(Duration.ofMillis(5));
					}
				});

		pool.start();
		try {
			assertTrue(pool.trySubmit(new ClaimedWork<>(new TestWork(1))));
			assertTrue(lifecycle.heartbeatCalled.await(1, TimeUnit.SECONDS));
			assertTrue(lifecycle.ownershipLost.get());
			assertFalse(lifecycle.doneCalled.await(100, TimeUnit.MILLISECONDS));
		}
		finally {
			pool.stop();
		}
	}

	private static SegmentedWorkerPool<TestWork, Integer> pool(
			RecordingLifecycle lifecycle,
			com.kartaguez.pocoma.orchestrator.claimable.work.WorkHandler<TestWork> handler) {
		return new SegmentedWorkerPool<>(
				lifecycle,
				handler,
				TestWork::key,
				new SegmentedWorkerPoolSettings(
						"test-pool",
						1,
						10,
						0,
						Duration.ZERO,
						Duration.ZERO,
						Duration.ofMillis(100),
						Duration.ofMillis(10)));
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(1, TimeUnit.SECONDS));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", exception);
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private record TestWork(int key) {
	}

	private static final class RecordingLifecycle implements ClaimableWorkLifecycle<TestWork, Object> {
		private final CountDownLatch heartbeatCalled = new CountDownLatch(1);
		private final CountDownLatch doneCalled = new CountDownLatch(1);
		private final AtomicBoolean heartbeatAccepted = new AtomicBoolean(true);
		private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
		private final AtomicInteger heartbeats = new AtomicInteger();

		@Override
		public List<ClaimedWork<TestWork>> claim(ClaimWorkRequest<Object> request) {
			return List.of();
		}

		@Override
		public boolean markAccepted(ClaimedWork<TestWork> work) {
			return true;
		}

		@Override
		public void release(ClaimedWork<TestWork> work) {
		}

		@Override
		public boolean markProcessing(ClaimedWork<TestWork> work) {
			return true;
		}

		@Override
		public boolean heartbeat(ClaimedWork<TestWork> work, Duration leaseDuration) {
			heartbeats.incrementAndGet();
			heartbeatCalled.countDown();
			boolean accepted = heartbeatAccepted.get();
			if (!accepted) {
				ownershipLost.set(true);
			}
			return accepted;
		}

		@Override
		public boolean markDone(ClaimedWork<TestWork> work) {
			doneCalled.countDown();
			return true;
		}

		@Override
		public boolean markFailed(ClaimedWork<TestWork> work, RuntimeException error) {
			return true;
		}
	}
}
