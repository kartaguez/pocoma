package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.key.CommandConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

public final class ReleaseCommandService implements ReleaseCommandUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public ReleaseCommandService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseCommandInput input) {
		requireNonNull(input, "input must not be null");
		return claimPort.releaseCurrentClaim(new CommandConsumptionKey(input.commandId()),
				input.claimToken(), clock.instant());
	}
}
