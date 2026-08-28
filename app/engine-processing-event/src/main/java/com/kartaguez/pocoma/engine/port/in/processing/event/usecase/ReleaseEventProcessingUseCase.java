package com.kartaguez.pocoma.engine.port.in.processing.event.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ReleaseEventProcessingInput;

@FunctionalInterface
public interface ReleaseEventProcessingUseCase {
	ConsumptionOutcome release(ReleaseEventProcessingInput input);
}
