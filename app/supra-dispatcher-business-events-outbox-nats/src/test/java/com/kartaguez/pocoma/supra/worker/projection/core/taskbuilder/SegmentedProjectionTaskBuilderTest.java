package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;

class SegmentedProjectionTaskBuilderTest {

	@Test
	void assignsSamePotToStableSegmentWithinThreadCount() {
		SegmentedProjectionTaskBuilder builder = builder(command -> {
		}, 10);
		PotId potId = PotId.of(UUID.randomUUID());

		int firstSegment = builder.segmentIndex(potId);
		int secondSegment = builder.segmentIndex(potId);

		assertEquals(firstSegment, secondSegment);
		assertTrue(firstSegment >= 0);
		assertTrue(firstSegment < 10);
	}

	@Test
	void processesSameSegmentClaimsInSubmissionOrder() throws InterruptedException {
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		PotId potId = PotId.of(UUID.randomUUID());
		SegmentedProjectionTaskBuilder builder = builder(command -> {
			versions.add(command.event().version());
			processed.countDown();
		}, 1);

		builder.start();
		try {
			builder.trySubmit(claim(potId, 2));
			builder.trySubmit(claim(potId, 3));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			builder.stop();
		}
	}

	@Test
	void trySubmitRefusesWhenSegmentQueueIsFull() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		ProjectionTaskBuilderSettings settings = settings(1, 1, 0);
		SegmentedProjectionTaskBuilder builder = new SegmentedProjectionTaskBuilder(useCase(command -> {
			started.countDown();
			await(unblock);
		}), settings);

		builder.start();
		try {
			assertTrue(builder.trySubmit(claim(potId, 2)));
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertTrue(builder.trySubmit(claim(potId, 3)));

			assertEquals(false, builder.trySubmit(claim(potId, 4)));
		}
		finally {
			unblock.countDown();
			builder.stop();
		}
	}

	@Test
	void keepsOtherSegmentsRunningWhenOneSegmentIsBlocked() throws InterruptedException {
		PotId blockedPotId = PotId.of(UUID.randomUUID());
		PotId freePotId = findPotIdInDifferentSegment(blockedPotId, 2);
		CountDownLatch blockedStarted = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		CountDownLatch freeProcessed = new CountDownLatch(1);
		SegmentedProjectionTaskBuilder builder = builder(command -> {
			if (command.event().potId().equals(blockedPotId)) {
				blockedStarted.countDown();
				await(unblock);
			}
			if (command.event().potId().equals(freePotId)) {
				freeProcessed.countDown();
			}
		}, 2);

		builder.start();
		try {
			builder.trySubmit(claim(blockedPotId, 2));
			assertTrue(blockedStarted.await(2, TimeUnit.SECONDS));

			builder.trySubmit(claim(freePotId, 2));

			assertTrue(freeProcessed.await(2, TimeUnit.SECONDS));
			assertNotEquals(builder.segmentIndex(blockedPotId), builder.segmentIndex(freePotId));
		}
		finally {
			unblock.countDown();
			builder.stop();
		}
	}

	@Test
	void retriesFailedBuildBeforeContinuingSegmentQueue() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		SegmentedProjectionTaskBuilder builder = new SegmentedProjectionTaskBuilder(useCase(command -> {
			if (command.event().version() == 2 && attempts.getAndIncrement() == 0) {
				throw new IllegalStateException("temporary failure");
			}
			versions.add(command.event().version());
			processed.countDown();
		}), settings(1, Integer.MAX_VALUE, 1));

		builder.start();
		try {
			builder.trySubmit(claim(potId, 2));
			builder.trySubmit(claim(potId, 3));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(2, attempts.get());
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			builder.stop();
		}
	}

	@Test
	void continuesAfterRetriesAreExhausted() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger failingAttempts = new AtomicInteger();
		CountDownLatch nextProcessed = new CountDownLatch(1);
		SegmentedProjectionTaskBuilder builder = new SegmentedProjectionTaskBuilder(useCase(command -> {
			if (command.event().version() == 2) {
				failingAttempts.incrementAndGet();
				throw new IllegalStateException("permanent failure");
			}
			nextProcessed.countDown();
		}), settings(1, Integer.MAX_VALUE, 1));

		builder.start();
		try {
			builder.trySubmit(claim(potId, 2));
			builder.trySubmit(claim(potId, 3));

			assertTrue(nextProcessed.await(2, TimeUnit.SECONDS));
			assertEquals(2, failingAttempts.get());
		}
		finally {
			builder.stop();
		}
	}

	@Test
	void rejectsInvalidSegmentedSettings() {
		assertThrows(IllegalArgumentException.class, () -> settings(0, 1, 0));
		assertThrows(IllegalArgumentException.class, () -> settings(1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> settings(1, 1, -1));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionTaskBuilderSettings(
				true,
				"test-builder",
				10,
				Duration.ofMillis(50),
				Duration.ofSeconds(30),
				ProjectionPartition.single(),
				true,
				1,
				1,
				0,
				Duration.ofMillis(2),
				Duration.ofMillis(1)));
	}

	private static SegmentedProjectionTaskBuilder builder(TaskBuild build, int threadCount) {
		return new SegmentedProjectionTaskBuilder(useCase(build), settings(threadCount, Integer.MAX_VALUE, 0));
	}

	private static ProjectionTaskBuilderSettings settings(int threadCount, int queueCapacity, int maxRetries) {
		return new ProjectionTaskBuilderSettings(
				true,
				"test-builder",
				10,
				Duration.ofMillis(50),
				Duration.ofSeconds(30),
				ProjectionPartition.single(),
				true,
				threadCount,
				queueCapacity,
				maxRetries,
				Duration.ZERO,
				Duration.ZERO);
	}

	private static BusinessEventClaim claim(PotId potId, long version) {
		return new BusinessEventClaim(event(potId, version), UUID.randomUUID());
	}

	private static BusinessEventEnvelope event(PotId potId, long version) {
		return new BusinessEventEnvelope(
				UUID.randomUUID(),
				"PotCreatedEvent",
				potId,
				potId.value(),
				version,
				"{}",
				null,
				null,
				Instant.now());
	}

	private static PotId findPotIdInDifferentSegment(PotId existingPotId, int threadCount) {
		SegmentedProjectionTaskBuilder builder = builder(command -> {
		}, threadCount);
		int existingSegment = builder.segmentIndex(existingPotId);
		for (int index = 0; index < 100; index++) {
			PotId candidate = PotId.of(UUID.randomUUID());
			if (builder.segmentIndex(candidate) != existingSegment) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not find potId in a different segment");
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

	private static BuildProjectionTasksUseCase useCase(TaskBuild build) {
		return new BuildProjectionTasksUseCase() {
			@Override
			public void buildProjectionTask(BuildProjectionTaskCommand command) {
				build.build(command);
			}
		};
	}

	@FunctionalInterface
	private interface TaskBuild {
		void build(BuildProjectionTaskCommand command);
	}
}
