package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;

public interface CompleteConsumptionUseCase {

	ConsumptionOutcome complete(CompleteConsumptionInput input);
}
