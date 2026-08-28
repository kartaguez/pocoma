package com.kartaguez.pocoma.engine.service.processing.event;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.CompleteEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.CompleteEventProcessingUseCase;

public final class CompleteEventProcessingService implements CompleteEventProcessingUseCase {

	private final CompleteConsumptionUseCase completeConsumptionUseCase;

	public CompleteEventProcessingService(CompleteConsumptionUseCase completeConsumptionUseCase) {
		this.completeConsumptionUseCase = requireNonNull(
				completeConsumptionUseCase, "completeConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteEventProcessingInput input) {
		requireNonNull(input, "input must not be null");
		return completeConsumptionUseCase.complete(new CompleteConsumptionInput(
				EventProcessingKeys.forEvent(input.pipeline(), input.eventId()), input.claimToken()));
	}
}
