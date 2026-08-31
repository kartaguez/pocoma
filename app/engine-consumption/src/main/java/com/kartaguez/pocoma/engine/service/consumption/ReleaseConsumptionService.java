package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

@Deprecated(forRemoval = true)
public final class ReleaseConsumptionService implements ReleaseConsumptionUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public ReleaseConsumptionService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		return claimPort.releaseCurrentClaim(input.consumptionKey(), input.claimToken(), clock.instant());
	}
}
