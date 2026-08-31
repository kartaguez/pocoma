package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;

@Deprecated(forRemoval = true)
public interface TryAcquireConsumptionUseCase {

	TryAcquireConsumptionResult tryAcquire(TryAcquireConsumptionInput input);
}
