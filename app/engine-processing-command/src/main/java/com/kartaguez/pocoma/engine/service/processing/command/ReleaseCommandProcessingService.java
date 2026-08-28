package com.kartaguez.pocoma.engine.service.processing.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ReleaseCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ReleaseCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseConsumptionUseCase;

public final class ReleaseCommandProcessingService implements ReleaseCommandProcessingUseCase {

	private final ReleaseConsumptionUseCase releaseConsumptionUseCase;

	public ReleaseCommandProcessingService(ReleaseConsumptionUseCase releaseConsumptionUseCase) {
		this.releaseConsumptionUseCase = requireNonNull(
				releaseConsumptionUseCase, "releaseConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseCommandProcessingInput input) {
		requireNonNull(input, "input must not be null");
		return releaseConsumptionUseCase.release(new ReleaseConsumptionInput(
				CommandProcessingKeys.forCommand(input.commandId()), input.claimToken()));
	}
}
