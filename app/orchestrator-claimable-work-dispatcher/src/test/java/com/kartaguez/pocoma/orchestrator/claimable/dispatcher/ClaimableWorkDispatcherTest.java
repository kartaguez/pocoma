package com.kartaguez.pocoma.orchestrator.claimable.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;

class ClaimableWorkDispatcherTest {

	@Test
	void claimsAcceptsAndSubmitsWork() {
		RecordingClaimWorkUseCase claimWorkUseCase = new RecordingClaimWorkUseCase();
		RecordingWorkHandler handler = new RecordingWorkHandler(10);
		ClaimableWorkDispatcher<TestWork, Integer, String, String> dispatcher =
				dispatcher(claimWorkUseCase, handler, WorkWakeBus.noop(), false);
		TestWork work = new TestWork(1);
		claimWorkUseCase.claims.add(work);

		int submitted = dispatcher.runOnce();

		assertEquals(1, submitted);
		assertEquals(List.of(work), claimWorkUseCase.accepted);
		assertEquals(List.of(work), handler.submitted);
		assertEquals(List.of(), claimWorkUseCase.released);
	}

	@Test
	void releasesWhenNoCapacityForWorkKey() {
		RecordingClaimWorkUseCase claimWorkUseCase = new RecordingClaimWorkUseCase();
		RecordingWorkHandler handler = new RecordingWorkHandler(10);
		handler.keyCapacity = 0;
		ClaimableWorkDispatcher<TestWork, Integer, String, String> dispatcher =
				dispatcher(claimWorkUseCase, handler, WorkWakeBus.noop(), false);
		TestWork work = new TestWork(1);
		claimWorkUseCase.claims.add(work);

		int submitted = dispatcher.runOnce();

		assertEquals(0, submitted);
		assertEquals(List.of(work), claimWorkUseCase.released);
		assertEquals(List.of(), claimWorkUseCase.accepted);
	}

	@Test
	void releasesWhenHandlerRefusesAfterAcceptance() {
		RecordingClaimWorkUseCase claimWorkUseCase = new RecordingClaimWorkUseCase();
		RecordingWorkHandler handler = new RecordingWorkHandler(10);
		handler.acceptSubmit = false;
		ClaimableWorkDispatcher<TestWork, Integer, String, String> dispatcher =
				dispatcher(claimWorkUseCase, handler, WorkWakeBus.noop(), false);
		TestWork work = new TestWork(1);
		claimWorkUseCase.claims.add(work);

		int submitted = dispatcher.runOnce();

		assertEquals(0, submitted);
		assertEquals(List.of(work), claimWorkUseCase.accepted);
		assertEquals(List.of(work), claimWorkUseCase.released);
	}

	@Test
	void wakeSignalRunsDispatcherLoop() throws InterruptedException {
		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		RecordingClaimWorkUseCase claimWorkUseCase = new RecordingClaimWorkUseCase();
		RecordingWorkHandler handler = new RecordingWorkHandler(10);
		ClaimableWorkDispatcher<TestWork, Integer, String, String> dispatcher =
				dispatcher(claimWorkUseCase, handler, wakeBus, true);
		TestWork work = new TestWork(1);

		dispatcher.start();
		try {
			assertTrue(claimWorkUseCase.claimAttempts.await(1, TimeUnit.SECONDS));
			claimWorkUseCase.claims.add(work);
			wakeBus.publish("WORK_AVAILABLE", work.key());

			assertTrue(handler.submittedLatch.await(1, TimeUnit.SECONDS));
			assertEquals(List.of(work), handler.submitted);
		}
		finally {
			dispatcher.stop();
		}
	}

	private static ClaimableWorkDispatcher<TestWork, Integer, String, String> dispatcher(
			RecordingClaimWorkUseCase claimWorkUseCase,
			RecordingWorkHandler handler,
			WorkWakeBus<String, Integer> wakeBus,
			boolean wakeSignalsEnabled) {
		return new ClaimableWorkDispatcher<>(
				claimWorkUseCase,
				handler,
				TestWork::key,
				"test-criteria",
				new ClaimableWorkDispatcherSettings(
						true,
						"test-worker",
						10,
						Duration.ofSeconds(30),
						Duration.ofMillis(50),
						wakeSignalsEnabled),
				wakeBus,
				Set.of("WORK_AVAILABLE"),
				key -> key % 2 == 1);
	}

	private record TestWork(int key) {
	}

	private static final class RecordingClaimWorkUseCase implements ClaimableWorkSource<TestWork, String> {
		private final Queue<TestWork> claims = new ConcurrentLinkedQueue<>();
		private final CountDownLatch claimAttempts = new CountDownLatch(1);
		private final List<TestWork> accepted = new ArrayList<>();
		private final List<TestWork> released = new ArrayList<>();

		@Override
		public List<ClaimedWork<TestWork>> claim(ClaimWorkRequest<String> request) {
			claimAttempts.countDown();
			TestWork work = claims.poll();
			if (work == null) {
				return List.of();
			}
			return List.of(new ClaimedWork<>(work));
		}

		@Override
		public boolean markAccepted(ClaimedWork<TestWork> work) {
			accepted.add(work.instruction());
			return true;
		}

		@Override
		public void release(ClaimedWork<TestWork> work) {
			released.add(work.instruction());
		}

		@Override
		public boolean markProcessing(ClaimedWork<TestWork> work) {
			return true;
		}

		@Override
		public boolean markDone(ClaimedWork<TestWork> work) {
			return true;
		}

		@Override
		public boolean markFailed(ClaimedWork<TestWork> work, RuntimeException error) {
			return true;
		}
	}

	private static final class RecordingWorkHandler implements SegmentedWorkHandler<ClaimedWork<TestWork>, Integer> {
		private final int capacity;
		private final CountDownLatch submittedLatch = new CountDownLatch(1);
		private final List<TestWork> submitted = new ArrayList<>();
		private boolean acceptSubmit = true;
		private int keyCapacity;
		private boolean running;

		private RecordingWorkHandler(int capacity) {
			this.capacity = capacity;
			this.keyCapacity = capacity;
		}

		@Override
		public boolean trySubmit(ClaimedWork<TestWork> work) {
			if (!acceptSubmit) {
				return false;
			}
			submitted.add(work.instruction());
			submittedLatch.countDown();
			return true;
		}

		@Override
		public int availableCapacity() {
			return capacity;
		}

		@Override
		public int availableCapacity(Integer key) {
			return keyCapacity;
		}

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}
	}
}
