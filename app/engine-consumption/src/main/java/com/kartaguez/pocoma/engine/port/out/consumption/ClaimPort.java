package com.kartaguez.pocoma.engine.port.out.consumption;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

/**
 * Atomic persistence boundary for consumption slots and claim history.
 *
 * <p>{@link #tryAcquire} must lazily create an absent slot and perform the
 * revision check, claim insertion, and slot update atomically.</p>
 */
public interface ClaimPort {

	Optional<ConsumptionSlot> findSlot(ConsumptionKey key);

	Optional<Claim> findClaim(com.kartaguez.pocoma.domain.consumption.claim.ClaimId claimId);

	Optional<Claim> tryAcquire(ConsumptionSlot observedSlot, Claim proposedClaim, Instant now);

	ConsumptionOutcome endCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now);

	ConsumptionOutcome failCurrentClaim(
			ConsumptionKey key, ClaimToken token, ProcessingFailure failure, Instant now);

	ConsumptionOutcome releaseCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now);
}
