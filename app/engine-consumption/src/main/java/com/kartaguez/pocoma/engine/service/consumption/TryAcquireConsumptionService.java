package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

public final class TryAcquireConsumptionService implements TryAcquireConsumptionUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public TryAcquireConsumptionService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		Instant now = clock.instant();
		ConsumptionSlot observedSlot = claimPort.findSlot(input.consumptionKey())
				.orElseGet(() -> ConsumptionSlot.initial(input.consumptionKey()));
		Claim proposedClaim = Claim.active(
				ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
				input.workerId(), now, input.lease());
		return claimPort.tryAcquire(observedSlot, proposedClaim, now);
	}
}
