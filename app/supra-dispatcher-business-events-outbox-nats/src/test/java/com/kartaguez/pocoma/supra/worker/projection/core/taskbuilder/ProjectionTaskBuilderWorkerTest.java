package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
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
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

class ProjectionTaskBuilderWorkerTest {

	@Test
	void businessEventSignalWakesBuilder() throws InterruptedException {
		InMemoryWorkWakeBus<String, PotId> wakeBus = new InMemoryWorkWakeBus<>();
		RecordingBuildUseCase useCase = new RecordingBuildUseCase();
		ProjectionTaskBuilderWorker worker = new ProjectionTaskBuilderWorker(
				useCase,
				useCase,
				settings(Duration.ofSeconds(5), true),
				wakeBus,
				ignored -> true);

		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			BusinessEventEnvelope event = event();
			useCase.claims.add(new BusinessEventClaim(event, UUID.randomUUID()));

			wakeBus.publish(ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE, event.potId());

			assertTrue(useCase.built.await(1, TimeUnit.SECONDS));
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
				useCase,
				settings(Duration.ofMillis(50), false),
				WorkWakeBus.noop(),
				ignored -> true);

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

	private static final class RecordingBuildUseCase
			implements BuildProjectionTasksUseCase, ClaimableWorkSource<BusinessEventEnvelope, ProjectionPartition> {
		private final Queue<BusinessEventClaim> claims = new ConcurrentLinkedQueue<>();
		private final CountDownLatch claimAttempts = new CountDownLatch(1);
		private final CountDownLatch built = new CountDownLatch(1);
		private int buildCount;

		@Override
		public List<ClaimedWork<BusinessEventEnvelope>> claim(ClaimWorkRequest<ProjectionPartition> request) {
			claimAttempts.countDown();
			BusinessEventClaim claim = claims.poll();
			if (claim == null) {
				return List.of();
			}
			return List.of(new ClaimedWork<>(claim.event()));
		}

		@Override
		public boolean markAccepted(ClaimedWork<BusinessEventEnvelope> work) {
			return true;
		}

		@Override
		public void release(ClaimedWork<BusinessEventEnvelope> work) {
		}

		@Override
		public boolean markProcessing(ClaimedWork<BusinessEventEnvelope> work) {
			return true;
		}

		@Override
		public void buildProjectionTask(BuildProjectionTaskCommand command) {
			buildCount++;
			built.countDown();
		}

		@Override
		public boolean markDone(ClaimedWork<BusinessEventEnvelope> work) {
			return true;
		}

		@Override
		public boolean markFailed(ClaimedWork<BusinessEventEnvelope> work, RuntimeException error) {
			return true;
		}
	}
}
