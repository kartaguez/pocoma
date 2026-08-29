package com.kartaguez.pocoma.engine.service.processing.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.FailCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.FailCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.command.CommandPort;

/** Commits the authoritative failure before its best-effort Command materialization. */
public final class FailCommandProcessingService implements FailCommandProcessingUseCase {

	private final CommandPort commandPort;
	private final FailConsumptionUseCase failConsumptionUseCase;

	public FailCommandProcessingService(
			CommandPort commandPort,
			FailConsumptionUseCase failConsumptionUseCase) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.failConsumptionUseCase = requireNonNull(
				failConsumptionUseCase, "failConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailCommandProcessingInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = failConsumptionUseCase.fail(new FailConsumptionInput(
				CommandProcessingKeys.forCommand(input.commandId()), input.claimToken(), input.failure()));
		if (outcome == ConsumptionOutcome.APPLIED) {
			commandPort.markFailed(input.commandId(), input.failure());
		}
		return outcome;
	}
}
