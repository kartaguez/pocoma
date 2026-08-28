package com.kartaguez.pocoma.engine.service.processing.event;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.FailEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.FailEventProcessingUseCase;

public final class FailEventProcessingService implements FailEventProcessingUseCase {

	private final FailConsumptionUseCase failConsumptionUseCase;

	public FailEventProcessingService(FailConsumptionUseCase failConsumptionUseCase) {
		this.failConsumptionUseCase = requireNonNull(
				failConsumptionUseCase, "failConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailEventProcessingInput input) {
		requireNonNull(input, "input must not be null");
		return failConsumptionUseCase.fail(new FailConsumptionInput(
				EventProcessingKeys.forEvent(input.pipeline(), input.eventId()),
				input.claimToken(), input.failure()));
	}
}
