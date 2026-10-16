package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;
import java.time.Clock;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;

/** Participant: executes business work once, then delegates the common terminal suffix. */
public final class ExecuteConsumptionService implements ExecuteConsumptionUseCase {
	private final FinalizeConsumptionUseCase finalizer;
	public ExecuteConsumptionService(FinalizeConsumptionUseCase finalizer) {
		this.finalizer = requireNonNull(finalizer);
	}
	public ExecuteConsumptionService(ConsumptionLifecyclePersistencePort lifecycle,
			ConsumptionProvenancePersistencePort provenance, Clock clock) {
		this(new FinalizeConsumptionService(lifecycle, provenance, clock));
	}
	@Override
	public ConsumptionExecutionResult execute(ExecuteConsumptionInput input) {
		requireNonNull(input);
		var result = requireNonNull(input.execution().execute(new ConsumptionExecutionContext(input.slotId(), input.claimId())));
		ConsumptionFinalization terminal = result.outcome() instanceof BusinessConsumptionOutcome.Success
				? new ConsumptionFinalization.Success()
				: new ConsumptionFinalization.Rejected(((BusinessConsumptionOutcome.Rejected) result.outcome()).rejectionCode());
		finalizer.finalizeConsumption(new FinalizeConsumptionInput(input.slotId(), input.claimId(), terminal,
				() -> {}, result.inputs(), result.results()));
		return result;
	}
			}
