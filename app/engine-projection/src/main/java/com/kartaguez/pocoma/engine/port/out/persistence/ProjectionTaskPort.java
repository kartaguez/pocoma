package com.kartaguez.pocoma.engine.port.out.persistence;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;

public interface ProjectionTaskPort {

	ProjectionTaskDescriptor upsertComputeBalancesTask(BusinessEventEnvelope sourceEvent);

	default List<ProjectionTaskClaim> claimPending(int limit, Duration leaseDuration, String workerId) {
		return claimPending(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	List<ProjectionTaskClaim> claimPending(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition);

	boolean markAccepted(UUID taskId, UUID claimToken);

	boolean markRunning(UUID taskId, UUID claimToken);

	boolean markDone(UUID taskId, UUID claimToken);

	boolean markFailed(UUID taskId, UUID claimToken, String error);

	boolean release(UUID taskId, UUID claimToken);

	boolean heartbeat(UUID taskId, UUID claimToken, Duration leaseDuration);

	long countPendingOrInProgress();

	long countPendingOrInProgress(PotId potId);
}
