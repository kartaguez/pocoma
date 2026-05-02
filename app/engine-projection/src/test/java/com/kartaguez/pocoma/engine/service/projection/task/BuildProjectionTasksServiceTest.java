package com.kartaguez.pocoma.engine.service.projection.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;

class BuildProjectionTasksServiceTest {

	@Test
	void publishesProjectionTasksReadyEventAfterTaskUpsert() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		BuildProjectionTasksService service = new BuildProjectionTasksService(
				taskPort,
				publisher);
		BusinessEventEnvelope event = event();
		ProjectionTaskDescriptor task = task(event);
		taskPort.task = task;

		service.buildProjectionTask(new BuildProjectionTaskCommand(event));

		assertEquals(1, publisher.readyEvents.size());
		assertEquals(new ProjectionTasksReadyEvent(task.id(), task.potId(), task.targetVersion(), task.sourceEventType()),
				publisher.readyEvents.getFirst());
	}

	@Test
	void doesNotPublishWhenUpsertFails() {
		RecordingProjectionTaskPort taskPort = new RecordingProjectionTaskPort();
		taskPort.failure = new IllegalStateException("upsert failed");
		RecordingProjectionEventPublisher publisher = new RecordingProjectionEventPublisher();
		BuildProjectionTasksService service = new BuildProjectionTasksService(
				taskPort,
				publisher);

		assertThrows(IllegalStateException.class, () -> service.buildProjectionTask(new BuildProjectionTaskCommand(event())));
		assertEquals(0, publisher.readyEvents.size());
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

	private static ProjectionTaskDescriptor task(BusinessEventEnvelope event) {
		return new ProjectionTaskDescriptor(
				UUID.randomUUID(),
				ProjectionTaskType.COMPUTE_BALANCES_FOR_VERSION,
				event.potId(),
				event.version(),
				event.eventType(),
				event.id(),
				event.id(),
				event.traceId(),
				event.commandCommittedAtNanos(),
				Instant.now());
	}

	private static final class RecordingProjectionEventPublisher implements ProjectionEventPublisherPort {
		private final List<ProjectionTasksReadyEvent> readyEvents = new ArrayList<>();

		@Override
		public void publish(ProjectionTasksReadyEvent event) {
			readyEvents.add(event);
		}
	}

	private static final class RecordingProjectionTaskPort implements ProjectionTaskPort {
		private ProjectionTaskDescriptor task;
		private RuntimeException failure;

		@Override
		public ProjectionTaskDescriptor upsertComputeBalancesTask(BusinessEventEnvelope sourceEvent) {
			if (failure != null) {
				throw failure;
			}
			return task;
		}

		@Override
		public List<ProjectionTaskClaim> claimPending(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			return List.of();
		}

		@Override
		public boolean markAccepted(UUID taskId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markRunning(UUID taskId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markDone(UUID taskId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markFailed(UUID taskId, UUID claimToken, String error) {
			return false;
		}

		@Override
		public boolean release(UUID taskId, UUID claimToken) {
			return false;
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

	private static final class NoopBusinessEventOutboxPort implements BusinessEventOutboxPort {
		@Override
		public void append(Object event) {
		}

		@Override
		public List<BusinessEventClaim> claimPending(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			return List.of();
		}

		@Override
		public boolean markAccepted(UUID eventId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markRunning(UUID eventId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markDone(UUID eventId, UUID claimToken) {
			return false;
		}

		@Override
		public boolean markFailed(UUID eventId, UUID claimToken, String error) {
			return false;
		}

		@Override
		public boolean release(UUID eventId, UUID claimToken) {
			return false;
		}

		@Override
		public long countPendingOrClaimed() {
			return 0;
		}
	}
}
