package com.kartaguez.pocoma.engine.port.in.processing.event.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.CompleteEventProcessingInput;

@FunctionalInterface
public interface CompleteEventProcessingUseCase {
	ConsumptionOutcome complete(CompleteEventProcessingInput input);
}
