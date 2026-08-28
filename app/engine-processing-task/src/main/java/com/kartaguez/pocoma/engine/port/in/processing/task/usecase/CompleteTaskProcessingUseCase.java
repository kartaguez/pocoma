package com.kartaguez.pocoma.engine.port.in.processing.task.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.CompleteTaskProcessingInput;

@FunctionalInterface
public interface CompleteTaskProcessingUseCase {
	ConsumptionOutcome complete(CompleteTaskProcessingInput input);
}
