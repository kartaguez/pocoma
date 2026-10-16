package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionQueryPort;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;

public final class HandleConsumptionFailureService implements HandleConsumptionFailureUseCase {

	private final ConsumptionQueryPort query;
	private final ConsumptionLifecyclePersistencePort persistence;
	private final ConsumptionFailurePolicy policy;
	private final Clock clock;
	private final FinalizeConsumptionUseCase finalizer;

	public HandleConsumptionFailureService(
			ConsumptionQueryPort query,
			ConsumptionLifecyclePersistencePort persistence,
			ConsumptionFailurePolicy policy,
			Clock clock) {
		this(query, persistence, policy, clock, new FinalizeConsumptionService(persistence, clock));
	}

	public HandleConsumptionFailureService(ConsumptionQueryPort query, ConsumptionLifecyclePersistencePort persistence, ConsumptionFailurePolicy policy, Clock clock, FinalizeConsumptionUseCase finalizer) {
		this.finalizer = requireNonNull(finalizer);
		this.query = requireNonNull(query, "query must not be null");
		this.persistence = requireNonNull(persistence, "persistence must not be null");
		this.policy = requireNonNull(policy, "policy must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public FencedMutationResult handle(HandleConsumptionFailureInput input) {
		requireNonNull(input, "input must not be null");
		Claim claim = query.findClaim(input.claimId())
				.orElseThrow(() -> new IllegalArgumentException("Unknown claim " + input.claimId().value()));
		if (!claim.slotId().equals(input.slotId())) {
			throw new IllegalArgumentException("Claim does not belong to slot " + input.slotId());
		}
		var now = clock.instant();
		FailureDecision decision = policy.decide(new FailureContext(input.failure(), claim.attemptNumber(), now));
		if (decision instanceof Fail) {
			return finalizer.finalizeConsumption(
					new FinalizeConsumptionInput(input.slotId(), input.claimId(),
							new TerminalFailure(input.failure()), input.terminalFailureEffect()));
		}
		var retry = (FailureDecision.RetryAfter) decision;
		persistence.scheduleRetry(input.slotId(), input.claimId(), input.failure(), now.plus(retry.duration()), now);
		return FencedMutationResult.APPLIED;
	}
}
