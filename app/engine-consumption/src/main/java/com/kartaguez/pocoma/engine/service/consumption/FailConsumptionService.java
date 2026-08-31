package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

@Deprecated(forRemoval = true)
public final class FailConsumptionService implements FailConsumptionUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public FailConsumptionService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		return claimPort.failCurrentClaim(
				input.consumptionKey(), input.claimToken(), input.failure(), clock.instant());
	}
}
