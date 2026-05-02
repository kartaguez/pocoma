package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

public final class ProjectionTaskWorkSource implements ClaimableWorkSource<ProjectionTask, ProjectionPartition> {

	private final ProjectionTaskPort projectionTaskPort;
	private final ProjectionEventPublisherPort eventPublisherPort;

	public ProjectionTaskWorkSource(
			ProjectionTaskPort projectionTaskPort,
			ProjectionEventPublisherPort eventPublisherPort) {
		this.projectionTaskPort = Objects.requireNonNull(projectionTaskPort, "projectionTaskPort must not be null");
		this.eventPublisherPort = Objects.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
	}

	@Override
	public List<ClaimedWork<ProjectionTask>> claim(ClaimWorkRequest<ProjectionPartition> request) {
		Objects.requireNonNull(request, "request must not be null");
		Duration leaseDuration = request.leaseDuration();
		return projectionTaskPort.claimPending(
				request.limit(),
				leaseDuration,
				request.workerId(),
				request.criteria())
				.stream()
				.map(this::toClaimedWork)
				.toList();
	}

	@Override
	public boolean markAccepted(ClaimedWork<ProjectionTask> work) {
		ProjectionTask task = work.instruction();
		return projectionTaskPort.markAccepted(task.taskId(), task.claimToken());
	}

	@Override
	public void release(ClaimedWork<ProjectionTask> work) {
		ProjectionTask task = work.instruction();
		projectionTaskPort.release(task.taskId(), task.claimToken());
	}

	@Override
	public boolean markProcessing(ClaimedWork<ProjectionTask> work) {
		ProjectionTask task = work.instruction();
		return projectionTaskPort.markRunning(task.taskId(), task.claimToken());
	}

	@Override
	public boolean markDone(ClaimedWork<ProjectionTask> work) {
		ProjectionTask task = work.instruction();
		boolean marked = projectionTaskPort.markDone(task.taskId(), task.claimToken());
		if (marked) {
			eventPublisherPort.publish(new ProjectionTaskProcessedEvent(
					task.taskId(),
					task.potId(),
					task.targetVersion(),
					task.sourceEventType(),
					ProjectionTaskStatus.DONE));
		}
		return marked;
	}

	@Override
	public boolean markFailed(ClaimedWork<ProjectionTask> work, RuntimeException error) {
		ProjectionTask task = work.instruction();
		boolean marked = projectionTaskPort.markFailed(task.taskId(), task.claimToken(), error.getMessage());
		if (marked) {
			eventPublisherPort.publish(new ProjectionTaskProcessedEvent(
					task.taskId(),
					task.potId(),
					task.targetVersion(),
					task.sourceEventType(),
					ProjectionTaskStatus.FAILED));
		}
		return marked;
	}

	private ClaimedWork<ProjectionTask> toClaimedWork(ProjectionTaskClaim claim) {
		ProjectionTaskDescriptor descriptor = claim.task();
		return new ClaimedWork<>(new ProjectionTask(
				descriptor.id(),
				claim.claimToken(),
				descriptor.potId(),
				descriptor.targetVersion(),
				descriptor.sourceEventType() == null ? descriptor.taskType().name() : descriptor.sourceEventType(),
				descriptor.traceId(),
				descriptor.commandCommittedAtNanos(),
				System.nanoTime()));
	}
}
