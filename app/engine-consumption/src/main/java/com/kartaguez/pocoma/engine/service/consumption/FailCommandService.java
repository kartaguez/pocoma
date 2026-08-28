package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.List;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;
import com.kartaguez.pocoma.engine.port.out.consumption.CommandPort;

public final class FailCommandService implements FailCommandUseCase {

	private final CommandPort commandPort;
	private final ClaimPort claimPort;
	private final Clock clock;

	public FailCommandService(CommandPort commandPort, ClaimPort claimPort, Clock clock) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailCommandInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = claimPort.failCurrentClaim(
				new ConsumptionKey("command", List.of(input.commandId().toString())),
				input.claimToken(), input.failure(), clock.instant());
		if (outcome == ConsumptionOutcome.APPLIED) {
			commandPort.markFailed(input.commandId(), input.failure());
		}
		return outcome;
	}
}
