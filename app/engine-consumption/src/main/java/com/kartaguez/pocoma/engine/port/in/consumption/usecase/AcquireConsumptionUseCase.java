package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;

@FunctionalInterface
public interface AcquireConsumptionUseCase {

	AcquireResult acquire(AcquireConsumptionInput input);
}
