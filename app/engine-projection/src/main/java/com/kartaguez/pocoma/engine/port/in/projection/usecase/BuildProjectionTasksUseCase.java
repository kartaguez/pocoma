package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;

public interface BuildProjectionTasksUseCase {

	default List<BusinessEventClaim> claimBusinessEvents(int limit, Duration leaseDuration, String workerId) {
		return claimBusinessEvents(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	default List<BusinessEventClaim> claimBusinessEvents(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		throw new UnsupportedOperationException("claimBusinessEvents is not implemented");
	}

	boolean markAccepted(UUID eventId, UUID claimToken);

	boolean markRunning(UUID eventId, UUID claimToken);

	void buildProjectionTask(BuildProjectionTaskCommand command);

	boolean markDone(UUID eventId, UUID claimToken);

	boolean markFailed(UUID eventId, UUID claimToken, String error);

	boolean release(UUID eventId, UUID claimToken);
}
