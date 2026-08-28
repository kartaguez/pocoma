package com.kartaguez.pocoma.engine.port.in.processing.event.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.FailEventProcessingInput;

@FunctionalInterface
public interface FailEventProcessingUseCase {
	ConsumptionOutcome fail(FailEventProcessingInput input);
}
