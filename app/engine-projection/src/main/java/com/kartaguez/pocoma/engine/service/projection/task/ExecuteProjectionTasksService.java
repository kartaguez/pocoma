package com.kartaguez.pocoma.engine.service.projection.task;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;

public final class ExecuteProjectionTasksService implements ExecuteProjectionTasksUseCase {

	private final ProjectionTaskPort projectionTaskPort;
	private final ComputePotBalancesUseCase computePotBalancesUseCase;

	public ExecuteProjectionTasksService(
			ProjectionTaskPort projectionTaskPort,
			ComputePotBalancesUseCase computePotBalancesUseCase) {
		this.projectionTaskPort = Objects.requireNonNull(projectionTaskPort, "projectionTaskPort must not be null");
		this.computePotBalancesUseCase = Objects.requireNonNull(
				computePotBalancesUseCase,
				"computePotBalancesUseCase must not be null");
	}

	@Override
	public List<ProjectionTaskClaim> claimProjectionTasks(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		return projectionTaskPort.claimPending(limit, leaseDuration, workerId, partition);
	}

	@Override
	public boolean markAccepted(UUID taskId, UUID claimToken) {
		return projectionTaskPort.markAccepted(taskId, claimToken);
	}

	@Override
	public boolean markRunning(UUID taskId, UUID claimToken) {
		return projectionTaskPort.markRunning(taskId, claimToken);
	}

	@Override
	public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		computePotBalancesUseCase.computePotBalances(command.potId(), command.targetVersion());
	}

	@Override
	public boolean markDone(UUID taskId, UUID claimToken) {
		return projectionTaskPort.markDone(taskId, claimToken);
	}

	@Override
	public boolean markFailed(UUID taskId, UUID claimToken, String error) {
		return projectionTaskPort.markFailed(taskId, claimToken, error);
	}

	@Override
	public boolean release(UUID taskId, UUID claimToken) {
		return projectionTaskPort.release(taskId, claimToken);
	}
}
