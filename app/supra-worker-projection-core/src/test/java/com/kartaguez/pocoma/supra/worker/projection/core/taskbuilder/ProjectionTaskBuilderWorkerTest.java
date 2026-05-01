package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.InMemoryProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;

class ProjectionTaskBuilderWorkerTest {

	@Test
	void businessEventSignalWakesBuilderAndBuilderWakesTaskExecutor() throws InterruptedException {
		InMemoryProjectionWorkerWakeBus wakeBus = new InMemoryProjectionWorkerWakeBus();
		RecordingBuildUseCase useCase = new RecordingBuildUseCase();
		CountDownLatch projectionTasksAvailable = new CountDownLatch(1);
		wakeBus.subscribe(
				Set.of(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE),
				ProjectionPartition.single(),
				projectionTasksAvailable::countDown);
		ProjectionTaskBuilderWorker worker = new ProjectionTaskBuilderWorker(
				useCase,
				settings(Duration.ofSeconds(5), true),
				wakeBus);

		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			BusinessEventEnvelope event = event();
			useCase.claims.add(new BusinessEventClaim(event, UUID.randomUUID()));

			wakeBus.publish(ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE, event.potId());

			assertTrue(useCase.built.await(1, TimeUnit.SECONDS));
			assertTrue(projectionTasksAvailable.await(1, TimeUnit.SECONDS));
			assertEquals(1, useCase.buildCount);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void timeoutEventuallyWakesBuilderWhenSignalsAreDisabled() throws InterruptedException {
		RecordingBuildUseCase useCase = new RecordingBuildUseCase();
		ProjectionTaskBuilderWorker worker = new ProjectionTaskBuilderWorker(
				useCase,
				settings(Duration.ofMillis(50), false),
				ProjectionWorkerWakeBus.noop());

		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			useCase.claims.add(new BusinessEventClaim(event(), UUID.randomUUID()));

			assertTrue(useCase.built.await(1, TimeUnit.SECONDS));
			assertEquals(1, useCase.buildCount);
		}
		finally {
			worker.stop();
		}
	}

	private static ProjectionTaskBuilderSettings settings(Duration pollingInterval, boolean wakeSignalsEnabled) {
		return new ProjectionTaskBuilderSettings(
				true,
				"test-builder",
				10,
				pollingInterval,
				Duration.ofSeconds(30),
				wakeSignalsEnabled);
	}

	private static BusinessEventEnvelope event() {
		PotId potId = PotId.of(UUID.randomUUID());
		return new BusinessEventEnvelope(
				UUID.randomUUID(),
				"PotCreatedEvent",
				potId,
				potId.value(),
				2,
				"{}",
				null,
				null,
				Instant.now());
	}

	private static final class RecordingBuildUseCase implements BuildProjectionTasksUseCase {
		private final Queue<BusinessEventClaim> claims = new ConcurrentLinkedQueue<>();
		private final CountDownLatch claimAttempts = new CountDownLatch(1);
		private final CountDownLatch built = new CountDownLatch(1);
		private int buildCount;

		@Override
		public List<BusinessEventClaim> claimBusinessEvents(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			claimAttempts.countDown();
			BusinessEventClaim claim = claims.poll();
			if (claim == null) {
				return List.of();
			}
			return List.of(claim);
		}

		@Override
		public boolean markAccepted(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markRunning(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public void buildProjectionTask(BuildProjectionTaskCommand command) {
			buildCount++;
			built.countDown();
		}

		@Override
		public boolean markDone(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markFailed(UUID eventId, UUID claimToken, String error) {
			return true;
		}

		@Override
		public boolean release(UUID eventId, UUID claimToken) {
			return true;
		}
	}
}
