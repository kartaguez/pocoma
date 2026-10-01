package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Rejected;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;

/** Coordinates business work, provenance and the final fencing CAS. */
public final class ExecuteConsumptionService implements ExecuteConsumptionUseCase {

	private final ConsumptionLifecyclePersistencePort lifecycle;
	private final ConsumptionProvenancePersistencePort provenance;
	private final Clock clock;

	public ExecuteConsumptionService(
			ConsumptionLifecyclePersistencePort lifecycle,
			ConsumptionProvenancePersistencePort provenance,
			Clock clock) {
		this.lifecycle = requireNonNull(lifecycle, "lifecycle must not be null");
		this.provenance = requireNonNull(provenance, "provenance must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionExecutionResult execute(ExecuteConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionExecutionResult result = requireNonNull(
				input.execution().execute(new ConsumptionExecutionContext(input.slotId(), input.claimId())),
				"execution result must not be null");
		validateSlotIds(input.slotId(), result);

		provenance.appendInputs(result.inputs());
		provenance.appendResults(result.results());
		TerminalOutcome terminalOutcome;
		Optional<TerminalReason> terminalReason;
		if (result.outcome() instanceof Success) {
			terminalOutcome = TerminalOutcome.SUCCESS;
			terminalReason = Optional.empty();
		} else {
			Rejected rejected = (Rejected) result.outcome();
			terminalOutcome = TerminalOutcome.REJECTED;
			terminalReason = Optional.of(new TerminalReason(rejected.rejectionCode()));
		}
		if (!lifecycle.tryTerminalize(
				input.slotId(), input.claimId(), terminalOutcome, terminalReason, clock.instant())) {
			throw new LostClaimException(input.slotId(), input.claimId());
		}
		return result;
	}

	private static void validateSlotIds(UUID expectedSlotId, ConsumptionExecutionResult result) {
		for (ConsumptionInput input : result.inputs()) {
			if (!input.slotId().equals(expectedSlotId)) {
				throw new IllegalArgumentException("ConsumptionInput belongs to another slot");
			}
		}
		for (ConsumptionResult produced : result.results()) {
			if (!produced.slotId().equals(expectedSlotId)) {
				throw new IllegalArgumentException("ConsumptionResult belongs to another slot");
			}
		}
	}
}
