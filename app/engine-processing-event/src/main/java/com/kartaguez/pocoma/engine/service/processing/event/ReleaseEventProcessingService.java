package com.kartaguez.pocoma.engine.service.processing.event;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ReleaseEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ReleaseEventProcessingUseCase;

public final class ReleaseEventProcessingService implements ReleaseEventProcessingUseCase {

	private final ReleaseConsumptionUseCase releaseConsumptionUseCase;

	public ReleaseEventProcessingService(ReleaseConsumptionUseCase releaseConsumptionUseCase) {
		this.releaseConsumptionUseCase = requireNonNull(
				releaseConsumptionUseCase, "releaseConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseEventProcessingInput input) {
		requireNonNull(input, "input must not be null");
		return releaseConsumptionUseCase.release(new ReleaseConsumptionInput(
				EventProcessingKeys.forEvent(input.pipeline(), input.eventId()), input.claimToken()));
	}
}
