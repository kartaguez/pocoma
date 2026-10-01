package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;

public final class FinalizeConsumptionService implements FinalizeConsumptionUseCase {
	private final ConsumptionLifecyclePersistencePort lifecycle;
	private final Clock clock;

	public FinalizeConsumptionService(ConsumptionLifecyclePersistencePort lifecycle, Clock clock) {
		this.lifecycle = requireNonNull(lifecycle, "lifecycle must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public FencedMutationResult finalizeConsumption(FinalizeConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		if (!lifecycle.lockCurrentClaim(input.slotId(), input.claimId())) {
			return FencedMutationResult.LOST_CLAIM;
		}
		input.durableEffect().apply();
		var now = clock.instant();
		if (input.finalization() instanceof Success) {
			if (!lifecycle.tryTerminalize(input.slotId(), input.claimId(), TerminalOutcome.SUCCESS,
					Optional.empty(), now)) {
				throw new IllegalStateException("Current Claim changed while its Slot was locked");
			}
			return FencedMutationResult.APPLIED;
		}
		var terminal = (TerminalFailure) input.finalization();
		FencedMutationResult result = lifecycle.handleFailure(
				input.slotId(), input.claimId(), terminal.failure(), new Fail(), now);
		if (result != FencedMutationResult.APPLIED) {
			throw new IllegalStateException("Current Claim changed while its Slot was locked");
		}
		return result;
	}
}
