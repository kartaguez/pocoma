package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.AbandonConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;

@FunctionalInterface
public interface AbandonConsumptionUseCase {

	AbandonResult abandon(AbandonConsumptionInput input);
}
