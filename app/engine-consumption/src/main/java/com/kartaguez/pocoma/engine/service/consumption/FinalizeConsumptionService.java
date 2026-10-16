package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;
import java.time.Clock;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import java.util.List;
import java.util.UUID;

/** Participant: effects and provenance precede the single terminal CAS/claim suffix. */
public final class FinalizeConsumptionService implements FinalizeConsumptionUseCase {
	private final ConsumptionLifecyclePersistencePort lifecycle;
	private final ConsumptionProvenancePersistencePort provenance;
	private final Clock clock;

	public FinalizeConsumptionService(ConsumptionLifecyclePersistencePort lifecycle,
			ConsumptionProvenancePersistencePort provenance, Clock clock) {
		this.lifecycle = requireNonNull(lifecycle);
		this.provenance = requireNonNull(provenance);
		this.clock = requireNonNull(clock);
	}

	/** For families without provenance; rejects nonempty provenance without an adapter. */
	public FinalizeConsumptionService(ConsumptionLifecyclePersistencePort lifecycle, Clock clock) {
		this(lifecycle, new ConsumptionProvenancePersistencePort() {
			public List<ConsumptionInput> findInputs(UUID slotId) { return List.of(); }
			public List<ConsumptionResult> findResults(UUID slotId) { return List.of(); }
			public void appendInputs(List<ConsumptionInput> inputs) {
				if (!inputs.isEmpty()) throw new IllegalStateException("Provenance adapter required");
			}
			public void appendResults(List<ConsumptionResult> results) {
				if (!results.isEmpty()) throw new IllegalStateException("Provenance adapter required");
			}
		}, clock);
	}

	@Override
	public FencedMutationResult finalizeConsumption(FinalizeConsumptionInput input) {
		requireNonNull(input);
		if (input.inputs().stream().anyMatch(i -> !i.slotId().equals(input.slotId()))
				|| input.results().stream().anyMatch(r -> !r.slotId().equals(input.slotId()))) {
			throw new IllegalArgumentException("Provenance belongs to another slot");
		}
		input.durableEffect().apply();
		provenance.appendInputs(input.inputs());
		provenance.appendResults(input.results());
		lifecycle.terminalize(input.slotId(), input.claimId(), input.finalization(), clock.instant());
		return FencedMutationResult.APPLIED;
	}
}
