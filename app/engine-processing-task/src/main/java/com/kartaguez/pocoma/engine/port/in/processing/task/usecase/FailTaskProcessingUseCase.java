package com.kartaguez.pocoma.engine.port.in.processing.task.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.FailTaskProcessingInput;

@FunctionalInterface
public interface FailTaskProcessingUseCase {
	ConsumptionOutcome fail(FailTaskProcessingInput input);
}
