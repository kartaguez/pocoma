package com.kartaguez.pocoma.pipeline.pot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

class PotPipelineTaskContractTest {
	private static final com.kartaguez.pocoma.domain.pipeline.PipelineDefinition PIPELINE =
			PotProjectionPipeline.definition(1);
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void schedulesAndMapsOnlyTheCommonExactExecutionPayload() {
		PotId potId = PotId.of(UUID.randomUUID());
		var descriptor = new PotTaskCreationStrategy(PIPELINE, json)
				.createTasks(new PotCreatedEvent(potId, 73)).getFirst();

		assertEquals(PotProjectionPipeline.TASK_TYPE, descriptor.taskType());
		assertEquals(73, descriptor.targetVersion());
		assertFalse(descriptor.taskPayload().contains("eventId"));
		var mapped = new ProjectPotRecordedTaskMapper(PIPELINE, json).map(new RecordedTask(
				UUID.randomUUID(), PIPELINE, potId, 73, Instant.parse("2026-01-01T00:00:00Z"),
				descriptor.taskType(), descriptor.taskPayload(), Optional.empty()));
		assertEquals(new ProjectPotTask(PIPELINE, potId, 73), mapped.task());
	}

	@Test
	void rejectsAnyPayloadThatDisagreesWithDurableIdentity() {
		PotId potId = PotId.of(UUID.randomUUID());
		String payload = "{\"pipelineId\":\"read-pot\",\"pipelineVersion\":1,\"potId\":\""
				+ potId.value() + "\",\"potVersion\":72}";
		var task = new RecordedTask(UUID.randomUUID(), PIPELINE, potId, 73,
				Instant.parse("2026-01-01T00:00:00Z"), PotProjectionPipeline.TASK_TYPE,
				payload, Optional.empty());
		assertThrows(ProjectPotRecordedTaskMapper.InvalidPotTaskException.class,
				() -> new ProjectPotRecordedTaskMapper(PIPELINE, json).map(task));
	}
}
