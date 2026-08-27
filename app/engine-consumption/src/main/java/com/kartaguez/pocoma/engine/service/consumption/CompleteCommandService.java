package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.key.CommandConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;
import com.kartaguez.pocoma.engine.port.out.consumption.CommandPort;

public final class CompleteCommandService implements CompleteCommandUseCase {

	private final CommandPort commandPort;
	private final ClaimPort claimPort;
	private final Clock clock;

	public CompleteCommandService(CommandPort commandPort, ClaimPort claimPort, Clock clock) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteCommandInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = claimPort.endCurrentClaim(
				new CommandConsumptionKey(input.commandId()), input.claimToken(), clock.instant());
		if (outcome == ConsumptionOutcome.APPLIED) {
			commandPort.markCompleted(input.commandId());
		}
		return outcome;
	}
}
