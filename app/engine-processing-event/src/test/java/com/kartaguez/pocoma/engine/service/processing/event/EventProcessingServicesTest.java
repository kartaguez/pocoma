package com.kartaguez.pocoma.engine.service.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.CompleteEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.FailEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ReleaseEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class EventProcessingServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerId WORKER = new WorkerId("event-worker");
	private static final PipelineDefinition BALANCES_V1 = pipeline("balances", 1);
	private static final PipelineDefinition BALANCES_V2 = pipeline("balances", 2);
	private static final PipelineDefinition SETTLEMENTS_V1 = pipeline("settlements", 1);

	@Test
	void pipelinesAndPipelineVersionsConsumeTheSameEventIndependently() {
		RecordedEvent<PotCreatedEvent> event = event(1, 1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		ClaimNextEventService service = new ClaimNextEventService(new InMemoryEventPort(event), consumption);

		EventClaimResult balancesV1 = service.claimNext(request(BALANCES_V1)).orElseThrow();
		EventClaimResult settlements = service.claimNext(request(SETTLEMENTS_V1)).orElseThrow();
		EventClaimResult balancesV2 = service.claimNext(request(BALANCES_V2)).orElseThrow();

		assertNotEquals(balancesV1.claim().consumptionKey(), settlements.claim().consumptionKey());
		assertNotEquals(balancesV1.claim().consumptionKey(), balancesV2.claim().consumptionKey());
		assertEquals(event, balancesV1.event());
		assertEquals(event, settlements.event());
	}

	@Test
	void segmentationUsesPipelineIdAndPotButNotPipelineVersion() {
		RecordedEvent<PotCreatedEvent> event = event(1, 1, NOW);
		int ownerV1 = owner(BALANCES_V1, event, 7);
		int ownerV2 = owner(BALANCES_V2, event, 7);
		int settlementsOwner = owner(SETTLEMENTS_V1, event, 7);

		assertEquals(ownerV1, ownerV2);
		InMemoryEventPort events = new InMemoryEventPort(event);
		for (int index = 0; index < 7; index++) {
			assertEquals(index == ownerV1, events.findNextCandidate(
					BALANCES_V1, new WorkerSegment(index, 7), Optional.empty()).isPresent());
		}
		assertEquals(Math.floorMod(PartitionHash.forPipelinePot(
				SETTLEMENTS_V1.pipelineId().value(), event.event().potId().value()).value(), 7), settlementsOwner);
	}

	@Test
	void aCasLoserContinuesInBusinessVersionCreationAndIdOrder() {
		RecordedEvent<PotCreatedEvent> first = event(1, 1, NOW.plusSeconds(10));
		RecordedEvent<PotCreatedEvent> second = event(2, 2, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		consumption.tryAcquire(new TryAcquireConsumptionInput(
				EventProcessingKeys.forEvent(BALANCES_V1, first.eventId()), new WorkerId("other"), LEASE));

		EventClaimResult result = new ClaimNextEventService(
				new InMemoryEventPort(second, first), consumption).claimNext(request(BALANCES_V1)).orElseThrow();

		assertEquals(second.eventId(), result.event().eventId());
	}

	@Test
	void anExpiredClaimIsReplacedWithANewToken() {
		RecordedEvent<PotCreatedEvent> event = event(1, 1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		ConsumptionKey key = EventProcessingKeys.forEvent(BALANCES_V1, event.eventId());
		Claim expired = Claim.active(ClaimId.generate(), key, ClaimToken.generate(), WORKER,
				NOW.minusSeconds(31), LEASE);
		consumption.claims.put(key, expired);

		Claim reclaimed = new ClaimNextEventService(new InMemoryEventPort(event), consumption)
				.claimNext(request(BALANCES_V1)).orElseThrow().claim();

		assertNotEquals(expired.token(), reclaimed.token());
	}

	@Test
	void transitionsAreScopedToOnePipelineAndRejectStaleTokens() {
		RecordedEvent<PotCreatedEvent> event = event(1, 1, NOW);
		InMemoryEventPort events = new InMemoryEventPort(event);
		InMemoryConsumption consumption = new InMemoryConsumption();
		Claim balances = consumption.acquire(BALANCES_V1, event.eventId());
		Claim settlements = consumption.acquire(SETTLEMENTS_V1, event.eventId());
		ProcessingFailure failure = new ProcessingFailure("strategy", "boom", NOW);

		assertEquals(ConsumptionOutcome.APPLIED,
				new CompleteEventProcessingService(consumption).complete(
						new CompleteEventProcessingInput(BALANCES_V1, event.eventId(), balances.token())));
		assertTrue(consumption.claims.get(settlements.consumptionKey()).isOwnedBy(settlements.token(), NOW));
		assertEquals(ConsumptionOutcome.APPLIED,
				new FailEventProcessingService(consumption).fail(
						new FailEventProcessingInput(SETTLEMENTS_V1, event.eventId(), settlements.token(), failure)));

		ClaimToken stale = ClaimToken.generate();
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteEventProcessingService(consumption).complete(
						new CompleteEventProcessingInput(BALANCES_V2, event.eventId(), stale)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new FailEventProcessingService(consumption).fail(
						new FailEventProcessingInput(BALANCES_V2, event.eventId(), stale, failure)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new ReleaseEventProcessingService(consumption).release(
						new ReleaseEventProcessingInput(BALANCES_V2, event.eventId(), stale)));
		assertEquals(0, events.transitionInvocations);
	}

	@Test
	void releaseLeavesOnlyTheSelectedPipelineConsumptionAvailableAgain() {
		RecordedEvent<PotCreatedEvent> event = event(1, 1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		Claim balances = consumption.acquire(BALANCES_V1, event.eventId());
		Claim settlements = consumption.acquire(SETTLEMENTS_V1, event.eventId());

		assertEquals(ConsumptionOutcome.APPLIED,
				new ReleaseEventProcessingService(consumption).release(
						new ReleaseEventProcessingInput(BALANCES_V1, event.eventId(), balances.token())));
		assertTrue(consumption.claims.get(settlements.consumptionKey()).isOwnedBy(settlements.token(), NOW));
		assertTrue(new ClaimNextEventService(new InMemoryEventPort(event), consumption)
				.claimNext(request(BALANCES_V1)).isPresent());
	}

	private static ClaimNextEventInput request(PipelineDefinition pipeline) {
		return new ClaimNextEventInput(WORKER, LEASE, WorkerSegment.single(), pipeline);
	}

	private static PipelineDefinition pipeline(String id, int version) {
		return new PipelineDefinition(PipelineId.of(id), version);
	}

	private static RecordedEvent<PotCreatedEvent> event(int id, long version, Instant recordedAt) {
		return new RecordedEvent<>(uuid(id), new PotCreatedEvent(PotId.of(uuid(100 + id)), version),
				recordedAt, EventTraceMetadata.empty());
	}

	private static EventOrderingKey ordering(RecordedEvent<? extends BusinessEvent> event) {
		return new EventOrderingKey(event.event().version(), event.recordedAt(), event.eventId());
	}

	private static int owner(PipelineDefinition pipeline,
			RecordedEvent<? extends BusinessEvent> event, int segmentCount) {
		return Math.floorMod(PartitionHash.forPipelinePot(
				pipeline.pipelineId().value(), event.event().potId().value()).value(), segmentCount);
	}

	private static UUID uuid(int suffix) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
	}

	private static final class InMemoryEventPort implements EventPort {
		private final List<RecordedEvent<? extends BusinessEvent>> events;
		private int transitionInvocations;

		@SafeVarargs
		private InMemoryEventPort(RecordedEvent<? extends BusinessEvent>... events) {
			this.events = List.of(events);
		}

		@Override
		public Optional<RecordedEvent<? extends BusinessEvent>> findNextCandidate(
				PipelineDefinition pipeline, WorkerSegment segment, Optional<EventOrderingKey> afterExclusive) {
			return events.stream()
					.filter(event -> segment.owns(PartitionHash.forPipelinePot(
							pipeline.pipelineId().value(), event.event().potId().value())))
					.filter(event -> afterExclusive.map(cursor -> ordering(event).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(EventProcessingServicesTest::ordering));
		}
	}

	private static final class InMemoryConsumption implements TryAcquireConsumptionUseCase,
			CompleteConsumptionUseCase, FailConsumptionUseCase, ReleaseConsumptionUseCase {

		private final Map<ConsumptionKey, Claim> claims = new HashMap<>();
		private final Set<ConsumptionKey> terminal = new HashSet<>();

		private Claim acquire(PipelineDefinition pipeline, UUID eventId) {
			return tryAcquire(new TryAcquireConsumptionInput(
					EventProcessingKeys.forEvent(pipeline, eventId), WORKER, LEASE)).orElseThrow();
		}

		@Override
		public synchronized Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
			if (terminal.contains(input.consumptionKey())) {
				return Optional.empty();
			}
			Claim current = claims.get(input.consumptionKey());
			if (current != null && current.isActiveAt(NOW)) {
				return Optional.empty();
			}
			Claim acquired = Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease());
			claims.put(input.consumptionKey(), acquired);
			return Optional.of(acquired);
		}

		@Override
		public synchronized ConsumptionOutcome complete(CompleteConsumptionInput input) {
			ConsumptionOutcome outcome = mutate(input.consumptionKey(), input.claimToken());
			if (outcome == ConsumptionOutcome.APPLIED) {
				terminal.add(input.consumptionKey());
			}
			return outcome;
		}

		@Override
		public synchronized ConsumptionOutcome fail(FailConsumptionInput input) {
			ConsumptionOutcome outcome = mutate(input.consumptionKey(), input.claimToken());
			if (outcome == ConsumptionOutcome.APPLIED) {
				terminal.add(input.consumptionKey());
			}
			return outcome;
		}

		@Override
		public synchronized ConsumptionOutcome release(ReleaseConsumptionInput input) {
			return mutate(input.consumptionKey(), input.claimToken());
		}

		private ConsumptionOutcome mutate(ConsumptionKey key, ClaimToken token) {
			Claim claim = claims.get(key);
			if (claim == null || !claim.isOwnedBy(token, NOW)) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			claims.put(key, claim.invalidateAt(NOW));
			return ConsumptionOutcome.APPLIED;
		}
	}
}
