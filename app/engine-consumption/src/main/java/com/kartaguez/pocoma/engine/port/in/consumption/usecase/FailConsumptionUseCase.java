package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;

@Deprecated(forRemoval = true)
public interface FailConsumptionUseCase {

	ConsumptionOutcome fail(FailConsumptionInput input);
}
