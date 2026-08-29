package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;

public interface TryAcquireConsumptionUseCase {

	TryAcquireConsumptionResult tryAcquire(TryAcquireConsumptionInput input);
}
