package com.kartaguez.pocoma.engine.service.projection.task;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;

public final class BuildProjectionTasksService implements BuildProjectionTasksUseCase {

	private final BusinessEventOutboxPort outboxPort;
	private final ProjectionTaskPort projectionTaskPort;

	public BuildProjectionTasksService(
			BusinessEventOutboxPort outboxPort,
			ProjectionTaskPort projectionTaskPort) {
		this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
		this.projectionTaskPort = Objects.requireNonNull(projectionTaskPort, "projectionTaskPort must not be null");
	}

	@Override
	public List<BusinessEventClaim> claimBusinessEvents(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		return outboxPort.claimPending(limit, leaseDuration, workerId, partition);
	}

	@Override
	public boolean markAccepted(UUID eventId, UUID claimToken) {
		return outboxPort.markAccepted(eventId, claimToken);
	}

	@Override
	public boolean markRunning(UUID eventId, UUID claimToken) {
		return outboxPort.markRunning(eventId, claimToken);
	}

	@Override
	public void buildProjectionTask(BuildProjectionTaskCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		projectionTaskPort.upsertComputeBalancesTask(command.event());
	}

	@Override
	public boolean markDone(UUID eventId, UUID claimToken) {
		return outboxPort.markDone(eventId, claimToken);
	}

	@Override
	public boolean markFailed(UUID eventId, UUID claimToken, String error) {
		return outboxPort.markFailed(eventId, claimToken, error);
	}

	@Override
	public boolean release(UUID eventId, UUID claimToken) {
		return outboxPort.release(eventId, claimToken);
	}
}
