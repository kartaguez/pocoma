package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;

@FunctionalInterface
public interface ExecuteConsumptionUseCase {

	ConsumptionExecutionResult execute(ExecuteConsumptionInput input);
}
