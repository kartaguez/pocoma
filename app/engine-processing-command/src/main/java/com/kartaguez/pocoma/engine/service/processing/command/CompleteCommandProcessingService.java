package com.kartaguez.pocoma.engine.service.processing.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.CompleteCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.CompleteCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.command.CommandPort;

public final class CompleteCommandProcessingService implements CompleteCommandProcessingUseCase {

	private final CommandPort commandPort;
	private final CompleteConsumptionUseCase completeConsumptionUseCase;

	public CompleteCommandProcessingService(
			CommandPort commandPort,
			CompleteConsumptionUseCase completeConsumptionUseCase) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.completeConsumptionUseCase = requireNonNull(
				completeConsumptionUseCase, "completeConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteCommandProcessingInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = completeConsumptionUseCase.complete(new CompleteConsumptionInput(
				CommandProcessingKeys.forCommand(input.commandId()), input.claimToken()));
		if (outcome == ConsumptionOutcome.APPLIED) {
			commandPort.markCompleted(input.commandId());
		}
		return outcome;
	}
}
