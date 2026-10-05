package com.kartaguez.pocoma.supra.consume.binding;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactCursor;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactDiscoveryPort;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactReadPort;
import com.kartaguez.pocoma.engine.materialize.currentbinding.MaterializeCurrentBindingService;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

public final class BindingFactConsumptionLocator implements ConsumptionLocator {
	public static final String CONSUMABLE_TYPE = "IDENTITY_BINDING_FACT";
	public static final String CONSUMER_TYPE = "CURRENT_BINDING_PROJECTOR";
	private final int segmentIndex, segmentCount;
	private final BindingFactDiscoveryPort discovery;
	private final BindingFactReadPort facts;
	private final MaterializeCurrentBindingService materializer;
	private final BindingFactFailureClassifier classifier;
	private final Clock clock;

	public BindingFactConsumptionLocator(int segmentIndex, int segmentCount, BindingFactDiscoveryPort discovery,
			BindingFactReadPort facts, MaterializeCurrentBindingService materializer, Clock clock) {
		this.segmentIndex = segmentIndex; this.segmentCount = segmentCount; this.discovery = discovery;
		this.facts = facts; this.materializer = materializer; this.clock = clock;
		this.classifier = new BindingFactFailureClassifier(clock);
	}

	@Override public ConsumptionSearch openSearch() {
		return new ConsumptionSearch() {
			private Optional<BindingFactCursor> cursor = Optional.empty();
			private final java.time.Instant now = clock.instant();
			@Override public Optional<LocatedConsumption> next() {
				var candidate = discovery.findNextEligible(segmentIndex, segmentCount, now, cursor);
				if (candidate.isEmpty()) return Optional.empty();
				var found = candidate.orElseThrow(); cursor = Optional.of(found.cursor());
				return Optional.of(new LocatedConsumption(key(found.eventId()),
						context -> execute(found.eventId(), context.slotId()), classifier));
			}
		};
	}

	private ConsumptionExecutionResult execute(UUID eventId, UUID slotId) {
		ExternalIdentityBindingFact fact = facts.findByEventId(eventId)
				.orElseThrow(() -> new BindingFactNotFoundException(eventId));
		materializer.apply(fact);
		// The provenance subject is the immutable fact (event_id), whose own version is always 1.
		// Binding revision may be 0 for a migration baseline and remains in the fact payload.
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),
				List.of(new ConsumptionInput(slotId, CONSUMABLE_TYPE, eventId.toString(), 1)),
				List.of());
	}

	public static ConsumptionKey key(UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity(CONSUMABLE_TYPE, List.of(eventId.toString())),
				new ConsumerIdentity(CONSUMER_TYPE, List.of()));
	}
}
