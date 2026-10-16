package com.kartaguez.pocoma.engine.port.out.consumption;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;

/** Atomic persistence boundary fenced exclusively by the current slot and ClaimId. */
public interface ConsumptionLifecyclePersistencePort {

	AcquireResult acquire(
			ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now);

	/** Participant: flush effects, CAS the slot, then require one claim closure; any loss throws. */
	void terminalize(UUID slotId, ClaimId claimId,
			ConsumptionFinalization finalization, Instant now);

	/** Same fenced closure, keeping PENDING and scheduling the next attempt without terminal effects. */
	void scheduleRetry(UUID slotId, ClaimId claimId, ProcessingFailure failure, Instant nextClaimAt, Instant now);

}
