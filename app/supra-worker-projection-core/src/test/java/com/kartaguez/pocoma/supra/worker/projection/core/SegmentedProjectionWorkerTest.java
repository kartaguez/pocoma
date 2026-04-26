package com.kartaguez.pocoma.supra.worker.projection.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;

class SegmentedProjectionWorkerTest {

	@Test
	void assignsSamePotToStableSegmentWithinThreadCount() {
		SegmentedProjectionWorker worker = worker((potId, targetVersion) -> balances(potId, targetVersion), 10);
		PotId potId = PotId.of(UUID.randomUUID());

		int firstSegment = worker.segmentIndex(potId);
		int secondSegment = worker.segmentIndex(potId);

		assertEquals(firstSegment, secondSegment);
		assertTrue(firstSegment >= 0);
		assertTrue(firstSegment < 10);
	}

	@Test
	void processesSameSegmentTasksInSubmissionOrder() throws InterruptedException {
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		PotId potId = PotId.of(UUID.randomUUID());
		SegmentedProjectionWorker worker = worker((receivedPotId, targetVersion) -> {
			versions.add(targetVersion);
			processed.countDown();
			return balances(receivedPotId, targetVersion);
		}, 1);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "FirstEvent"));
			worker.submit(new ProjectionTask(potId, 3, "SecondEvent"));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void keepsOtherSegmentsRunningWhenOneSegmentIsBlocked() throws InterruptedException {
		PotId blockedPotId = PotId.of(UUID.randomUUID());
		PotId freePotId = findPotIdInDifferentSegment(blockedPotId, 2);
		CountDownLatch blockedStarted = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		CountDownLatch freeProcessed = new CountDownLatch(1);
		SegmentedProjectionWorker worker = worker((potId, targetVersion) -> {
			if (potId.equals(blockedPotId)) {
				blockedStarted.countDown();
				await(unblock);
			}
			if (potId.equals(freePotId)) {
				freeProcessed.countDown();
			}
			return balances(potId, targetVersion);
		}, 2);

		worker.start();
		try {
			worker.submit(new ProjectionTask(blockedPotId, 2, "BlockedEvent"));
			assertTrue(blockedStarted.await(2, TimeUnit.SECONDS));

			worker.submit(new ProjectionTask(freePotId, 2, "FreeEvent"));

			assertTrue(freeProcessed.await(2, TimeUnit.SECONDS));
			assertNotEquals(worker.segmentIndex(blockedPotId), worker.segmentIndex(freePotId));
		}
		finally {
			unblock.countDown();
			worker.stop();
		}
	}

	@Test
	void retriesFailedTaskBeforeContinuingSegmentQueue() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		ProjectionWorkerSettings settings = settings(1, 1);
		SegmentedProjectionWorker worker = new SegmentedProjectionWorker((receivedPotId, targetVersion) -> {
			if (targetVersion == 2 && attempts.getAndIncrement() == 0) {
				throw new IllegalStateException("temporary failure");
			}
			versions.add(targetVersion);
			processed.countDown();
			return balances(receivedPotId, targetVersion);
		}, settings);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "RetriedEvent"));
			worker.submit(new ProjectionTask(potId, 3, "NextEvent"));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(2, attempts.get());
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void continuesAfterRetriesAreExhausted() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger failingAttempts = new AtomicInteger();
		CountDownLatch nextProcessed = new CountDownLatch(1);
		ProjectionWorkerSettings settings = settings(1, 1);
		SegmentedProjectionWorker worker = new SegmentedProjectionWorker((receivedPotId, targetVersion) -> {
			if (targetVersion == 2) {
				failingAttempts.incrementAndGet();
				throw new IllegalStateException("permanent failure");
			}
			nextProcessed.countDown();
			return balances(receivedPotId, targetVersion);
		}, settings);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "FailingEvent"));
			worker.submit(new ProjectionTask(potId, 3, "NextEvent"));

			assertTrue(nextProcessed.await(2, TimeUnit.SECONDS));
			assertEquals(2, failingAttempts.get());
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void rejectsInvalidSettings() {
		assertThrows(IllegalArgumentException.class, () -> settings(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerSettings(
				1,
				0,
				0,
				Duration.ZERO,
				Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerSettings(
				1,
				1,
				-1,
				Duration.ZERO,
				Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionWorkerSettings(
				1,
				1,
				0,
				Duration.ofMillis(2),
				Duration.ofMillis(1)));
	}

	private static SegmentedProjectionWorker worker(ComputePotBalancesUseCase useCase, int threadCount) {
		return new SegmentedProjectionWorker(useCase, settings(threadCount, 0));
	}

	private static ProjectionWorkerSettings settings(int threadCount, int maxRetries) {
		return new ProjectionWorkerSettings(threadCount, Integer.MAX_VALUE, maxRetries, Duration.ZERO, Duration.ZERO);
	}

	private static PotId findPotIdInDifferentSegment(PotId existingPotId, int threadCount) {
		SegmentedProjectionWorker worker = worker((potId, targetVersion) -> balances(potId, targetVersion), threadCount);
		int existingSegment = worker.segmentIndex(existingPotId);
		for (int index = 0; index < 100; index++) {
			PotId candidate = PotId.of(UUID.randomUUID());
			if (worker.segmentIndex(candidate) != existingSegment) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not find potId in a different segment");
	}

	private static PotBalances balances(PotId potId, long version) {
		return new PotBalances(potId, version, Map.of());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
