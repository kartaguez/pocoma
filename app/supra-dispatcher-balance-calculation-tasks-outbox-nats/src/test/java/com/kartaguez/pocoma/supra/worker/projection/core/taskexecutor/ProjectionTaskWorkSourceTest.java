package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

class ProjectionTaskWorkSourceTest {

	@Test
	void publishesProcessedEventAfterDoneTransition() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		taskPort.markDoneResult = true;
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		ProjectionTaskWorkSource source = new ProjectionTaskWorkSource(taskPort, publisher);
		ClaimedWork<ProjectionTask> work = new ClaimedWork<>(task());

		boolean marked = source.markDone(work);

		assertEquals(true, marked);
		assertEquals(List.of(new ProjectionTaskProcessedEvent(
				work.instruction().taskId(),
				work.instruction().potId(),
				work.instruction().targetVersion(),
				work.instruction().sourceEventType(),
				ProjectionTaskStatus.DONE)), publisher.processedEvents);
	}

	@Test
	void publishesProcessedEventAfterFailedTransition() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		taskPort.markFailedResult = true;
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		ProjectionTaskWorkSource source = new ProjectionTaskWorkSource(taskPort, publisher);
		ClaimedWork<ProjectionTask> work = new ClaimedWork<>(task());

		boolean marked = source.markFailed(work, new IllegalStateException("boom"));

		assertEquals(true, marked);
		assertEquals(List.of(new ProjectionTaskProcessedEvent(
				work.instruction().taskId(),
				work.instruction().potId(),
				work.instruction().targetVersion(),
				work.instruction().sourceEventType(),
				ProjectionTaskStatus.FAILED)), publisher.processedEvents);
	}

	@Test
	void doesNotPublishWhenDoneTransitionIsRefused() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		ProjectionTaskWorkSource source = new ProjectionTaskWorkSource(taskPort, publisher);

		boolean marked = source.markDone(new ClaimedWork<>(task()));

		assertEquals(false, marked);
		assertEquals(List.of(), publisher.processedEvents);
	}

	@Test
	void doesNotPublishWhenFailedTransitionIsRefused() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		ProjectionTaskWorkSource source = new ProjectionTaskWorkSource(taskPort, publisher);

		boolean marked = source.markFailed(new ClaimedWork<>(task()), new IllegalStateException("boom"));

		assertEquals(false, marked);
		assertEquals(List.of(), publisher.processedEvents);
	}

	private static ProjectionTask task() {
		return new ProjectionTask(
				UUID.randomUUID(),
				UUID.randomUUID(),
				PotId.of(UUID.randomUUID()),
				12,
				"PotCreatedEvent",
				null,
				null,
				System.nanoTime());
	}

	private static final class RecordingProjectionEventPublisher implements ProjectionEventPublisherPort {
		private final List<ProjectionTaskProcessedEvent> processedEvents = new ArrayList<>();

		@Override
		public void publish(ProjectionTaskProcessedEvent event) {
			processedEvents.add(event);
		}
	}

	private static final class RecordingProjectionTaskPort implements ProjectionTaskPort {
		private boolean markDoneResult;
		private boolean markFailedResult;

		@Override
		public ProjectionTaskDescriptor upsertComputeBalancesTask(BusinessEventEnvelope sourceEvent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ProjectionTaskClaim> claimPending(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			return List.of(new ProjectionTaskClaim(
					new ProjectionTaskDescriptor(
							UUID.randomUUID(),
							ProjectionTaskType.COMPUTE_BALANCES_FOR_VERSION,
							PotId.of(UUID.randomUUID()),
							12,
							"PotCreatedEvent",
							UUID.randomUUID(),
							UUID.randomUUID(),
							null,
							null,
							Instant.now()),
					UUID.randomUUID()));
		}

		@Override
		public boolean markAccepted(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markRunning(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markDone(UUID taskId, UUID claimToken) {
			return markDoneResult;
		}

		@Override
		public boolean markFailed(UUID taskId, UUID claimToken, String error) {
			return markFailedResult;
		}

		@Override
		public boolean release(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean heartbeat(UUID taskId, UUID claimToken, Duration leaseDuration) {
			return false;
		}

		@Override
		public long countPendingOrInProgress() {
			return 0;
		}

		@Override
		public long countPendingOrInProgress(PotId potId) {
			return 0;
		}
	}
}
