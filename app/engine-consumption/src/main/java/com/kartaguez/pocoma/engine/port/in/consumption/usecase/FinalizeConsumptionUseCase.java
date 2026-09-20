package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;

public interface FinalizeConsumptionUseCase {
	FencedMutationResult finalizeConsumption(FinalizeConsumptionInput input);
}
