package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

public final class CompleteConsumptionService implements CompleteConsumptionUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public CompleteConsumptionService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		return claimPort.endCurrentClaim(input.consumptionKey(), input.claimToken(), clock.instant());
	}
}
