package com.kartaguez.pocoma.engine.port.out.persistence;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public interface BusinessEventOutboxPort {

	void append(Object event);

	default List<BusinessEventClaim> claimPending(int limit, Duration leaseDuration, String workerId) {
		return claimPending(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	List<BusinessEventClaim> claimPending(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition);

	boolean markAccepted(UUID eventId, UUID claimToken);

	boolean markRunning(UUID eventId, UUID claimToken);

	boolean markDone(UUID eventId, UUID claimToken);

	boolean markFailed(UUID eventId, UUID claimToken, String error);

	boolean release(UUID eventId, UUID claimToken);

	long countPendingOrClaimed();
}
