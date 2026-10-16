package com.kartaguez.pocoma.engine.exception.consumption;

import java.util.UUID;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;

/** Integrity failure after a winning slot CAS; never a normal ownership loss. */
public final class ConsumptionClaimClosureException extends IllegalStateException {
	public ConsumptionClaimClosureException(String operation, UUID slotId, ClaimId claimId, Integer cardinality, Throwable cause) {
		super(operation + " slot=" + slotId + " claim=" + claimId.value() + " cardinality=" + cardinality, cause);
	}
}
