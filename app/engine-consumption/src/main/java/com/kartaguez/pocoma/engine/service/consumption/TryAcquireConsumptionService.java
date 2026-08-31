package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.exception.MissingTerminalConsumptionFailureException;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyCompleted;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyFailed;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.NotAcquiredBusy;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

@Deprecated(forRemoval = true)
public final class TryAcquireConsumptionService implements TryAcquireConsumptionUseCase {

	private final ClaimPort claimPort;
	private final Clock clock;

	public TryAcquireConsumptionService(ClaimPort claimPort, Clock clock) {
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public TryAcquireConsumptionResult tryAcquire(TryAcquireConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		Instant now = clock.instant();
		ConsumptionSlot observedSlot = claimPort.findSlot(input.consumptionKey())
				.orElseGet(() -> ConsumptionSlot.initial(input.consumptionKey()));
		Optional<TryAcquireConsumptionResult> terminalResult = terminalResult(observedSlot);
		if (terminalResult.isPresent()) {
			return terminalResult.orElseThrow();
		}
		Claim proposedClaim = Claim.active(
				ClaimId.generate(), input.consumptionKey(), input.workerId(), 1, now, input.lease());
		Optional<Claim> acquiredClaim = claimPort.tryAcquire(observedSlot, proposedClaim, now);
		if (acquiredClaim.isPresent()) {
			return new Acquired(acquiredClaim.orElseThrow());
		}
		return claimPort.findSlot(input.consumptionKey())
				.flatMap(this::terminalResult)
				.orElseGet(NotAcquiredBusy::new);
	}

	private Optional<TryAcquireConsumptionResult> terminalResult(ConsumptionSlot slot) {
		if (slot.status() == ConsumptionStatus.DONE
				&& slot.terminalOutcome().filter(outcome -> outcome != TerminalOutcome.FAILED).isPresent()) {
			return Optional.of(new AlreadyCompleted());
		}
		if (slot.status() == ConsumptionStatus.DONE
				&& slot.terminalOutcome().filter(outcome -> outcome == TerminalOutcome.FAILED).isPresent()) {
			return Optional.of(new AlreadyFailed(claimPort.findTerminalFailure(slot.consumptionKey())
					.orElseThrow(() -> new MissingTerminalConsumptionFailureException(slot.consumptionKey()))));
		}
		return Optional.empty();
	}
}
