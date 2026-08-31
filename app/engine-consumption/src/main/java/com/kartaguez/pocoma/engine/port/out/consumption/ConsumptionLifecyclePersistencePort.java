package com.kartaguez.pocoma.engine.port.out.consumption;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;

/** Target atomic persistence boundary. It never uses the legacy ClaimToken. */
public interface ConsumptionLifecyclePersistencePort {

	AcquireResult acquire(
			ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now);

	boolean tryTerminalize(
			UUID slotId, ClaimId claimId, TerminalOutcome outcome, Instant doneAt);

	FencedMutationResult handleFailure(
			UUID slotId,
			ClaimId claimId,
			ProcessingFailure failure,
			FailureDecision decision,
			Instant now);

	AbandonResult abandon(UUID slotId, Instant now);
}
