package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;

@FunctionalInterface
public interface HandleConsumptionFailureUseCase {

	FencedMutationResult handle(HandleConsumptionFailureInput input);
}
