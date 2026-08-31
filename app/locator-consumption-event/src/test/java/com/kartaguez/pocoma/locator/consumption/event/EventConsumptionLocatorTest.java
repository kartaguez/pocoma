package com.kartaguez.pocoma.locator.consumption.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.PersistedTaskReference;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class EventConsumptionLocatorTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

	@Test
	void buildsStructuralKeyAndWinningProvenanceFromPersistedTasks() {
		UUID eventId = UUID.randomUUID();
		PotId potId = PotId.of(UUID.randomUUID());
		var event = new RecordedEvent<>(eventId, new PotCreatedEvent(potId, 7), NOW,
				EventTraceMetadata.empty());
		var pipeline = new PipelineDefinition(PipelineId.of("balances"), 3);
		UUID taskId = UUID.randomUUID();
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(),
				(p, s, cursor) -> cursor.isEmpty() ? Optional.of(event) : Optional.empty(),
				input -> new TaskCreationResult(eventId, pipeline, TaskCreationOutcome.CREATED,
						List.of(new PersistedTaskReference(taskId, "COMPUTE_BALANCES", NOW.plusSeconds(1)))),
				new EventConsumptionFailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC)));

		var located = locator.openSearch().next().orElseThrow();
		assertEquals("EVENT", located.consumptionKey().consumable().type());
		assertEquals(List.of(eventId.toString()), located.consumptionKey().consumable().components());
		assertEquals("PIPELINE", located.consumptionKey().consumer().type());
		assertEquals(List.of("balances", "3"), located.consumptionKey().consumer().components());

		UUID slotId = UUID.randomUUID();
		var result = located.execution().execute(new ConsumptionExecutionContext(slotId,
				new ClaimId(UUID.randomUUID())));
		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(List.of(new com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput(
				slotId, "EVENT", eventId.toString(), 7)), result.inputs());
		assertEquals(1, result.results().size());
		assertEquals(taskId.toString(), result.results().getFirst().objectId());
		assertEquals(Optional.of("POT"), result.results().getFirst().subjectType());
		assertEquals(Optional.of(potId.value().toString()), result.results().getFirst().subjectId());
	}

	@Test
	void zeroTaskPlanIsStillSuccess() {
		UUID eventId = UUID.randomUUID();
		var pipeline = new PipelineDefinition(PipelineId.of("empty"), 1);
		var event = new RecordedEvent<>(eventId, new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1), NOW,
				EventTraceMetadata.empty());
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(),
				(p, s, cursor) -> Optional.of(event),
				input -> new TaskCreationResult(eventId, pipeline, TaskCreationOutcome.CREATED, List.of()),
				failure -> { throw new AssertionError(); });
		var result = locator.openSearch().next().orElseThrow().execution().execute(
				new ConsumptionExecutionContext(UUID.randomUUID(), new ClaimId(UUID.randomUUID())));
		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(List.of(), result.results());
	}
}
