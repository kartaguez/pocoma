package com.kartaguez.pocoma.locator.consumption.latestknownversion;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.LatestKnownVersionEventDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionUseCase;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

public final class LatestKnownVersionConsumptionLocator implements ConsumptionLocator {
	public static final String CONSUMER_TYPE = "SOURCE_VERSION_WATERMARK";

	private final WorkerSegment segment;
	private final LatestKnownVersionEventDiscoveryPort discovery;
	private final EventPort events;
	private final AdvanceLatestKnownVersionUseCase advance;
	private final ConsumptionTechnicalFailureClassifier failureClassifier;
	private final Clock clock;

	public LatestKnownVersionConsumptionLocator(WorkerSegment segment,
			LatestKnownVersionEventDiscoveryPort discovery, EventPort events,
			AdvanceLatestKnownVersionUseCase advance, ConsumptionTechnicalFailureClassifier failureClassifier, Clock clock) {
		this.segment = requireNonNull(segment, "segment must not be null");
		this.discovery = requireNonNull(discovery, "discovery must not be null");
		this.events = requireNonNull(events, "events must not be null");
		this.advance = requireNonNull(advance, "advance must not be null");
		this.failureClassifier = requireNonNull(failureClassifier, "failureClassifier must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionSearch openSearch() {
		return new Search();
	}

	private final class Search implements ConsumptionSearch {
		private Optional<EventOrderingKey> cursor = Optional.empty();
		private final Instant now = clock.instant();

		@Override
		public Optional<LocatedConsumption> next() {
			var candidate = discovery.findNextEligibleCandidate(segment, now, cursor);
			if (candidate.isEmpty()) return Optional.empty();
			var event = candidate.orElseThrow();
			cursor = Optional.of(event.orderingKey());
			UUID eventId = event.eventId();
			return Optional.of(new LocatedConsumption(key(eventId), context -> execute(eventId, context.slotId()),
					failureClassifier));
		}
	}

	private ConsumptionExecutionResult execute(UUID eventId, UUID slotId) {
		RecordedEvent<? extends BusinessEvent> recorded = events.findById(eventId)
				.orElseThrow(() -> new RecordedEventNotFoundException(eventId));
		BusinessEvent event = recorded.event();
		advance.advanceToAtLeast(new AdvanceLatestKnownVersionInput(event.potId(), event.version(), clock.instant()));
		var input = new ConsumptionInput(slotId, "EVENT", eventId.toString(), event.version());
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(input), List.of());
	}

	public static ConsumptionKey key(UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity(CONSUMER_TYPE, List.of()));
	}
}
