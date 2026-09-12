package com.kartaguez.pocoma.locator.consumption.latestknownversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionCandidate;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionUpdate;

class LatestKnownVersionConsumptionLocatorTest {

	@Test
	void reloadsTheEventAndProducesNoArtificialConsumptionResult() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		Clock clock = Clock.fixed(now, ZoneOffset.UTC);
		UUID eventId = UUID.randomUUID();
		PotId potId = PotId.of(UUID.randomUUID());
		var candidate = new EventConsumptionCandidate(eventId, potId, 42, now);
		var captured = new AtomicReference<AdvanceLatestKnownVersionInput>();
		var locator = new LatestKnownVersionConsumptionLocator(WorkerSegment.single(),
				(segment, instant, cursor) -> Optional.of(candidate),
				id -> Optional.of(new RecordedEvent<>(eventId, new PotCreatedEvent(potId, 42), now,
						EventTraceMetadata.empty())),
				input -> {
					captured.set(input);
					return new LatestKnownVersionUpdate.Advanced(new LatestKnownVersion(potId, 42));
				}, new LatestKnownVersionFailureClassifier(clock), clock);

		var located = locator.openSearch().next().orElseThrow();
		UUID slotId = UUID.randomUUID();
		var result = located.execution().execute(new ConsumptionExecutionContext(slotId,
				new com.kartaguez.pocoma.domain.consumption.claim.ClaimId(UUID.randomUUID())));

		assertEquals(new AdvanceLatestKnownVersionInput(potId, 42, now), captured.get());
		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(1, result.inputs().size());
		assertTrue(result.results().isEmpty());
		assertEquals(LatestKnownVersionConsumptionLocator.key(eventId), located.consumptionKey());
	}
}
