package com.kartaguez.pocoma.engine.port.out.consumption;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

public interface ConsumptionQueryPort {

	Optional<ConsumptionSlot> findSlot(UUID slotId);

	Optional<ConsumptionSlot> findSlot(ConsumptionKey key);

	Optional<Claim> findClaim(ClaimId claimId);

	List<Claim> findClaims(UUID slotId);
}
