package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;

public interface ExecuteProjectionTasksUseCase {

	default List<ProjectionTaskClaim> claimProjectionTasks(int limit, Duration leaseDuration, String workerId) {
		return claimProjectionTasks(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	default List<ProjectionTaskClaim> claimProjectionTasks(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		throw new UnsupportedOperationException("claimProjectionTasks is not implemented");
	}

	boolean markAccepted(UUID taskId, UUID claimToken);

	boolean markRunning(UUID taskId, UUID claimToken);

	void executeProjectionTask(ExecuteProjectionTaskCommand command);

	boolean markDone(UUID taskId, UUID claimToken);

	boolean markFailed(UUID taskId, UUID claimToken, String error);

	boolean release(UUID taskId, UUID claimToken);
}
