package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;

/** Provenance and business conclusion produced by one successful callback. */
public record ConsumptionExecutionResult(
		BusinessConsumptionOutcome outcome,
		List<ConsumptionInput> inputs,
		List<ConsumptionResult> results) {

	public ConsumptionExecutionResult {
		requireNonNull(outcome, "outcome must not be null");
		inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
		results = List.copyOf(requireNonNull(results, "results must not be null"));
	}
}
