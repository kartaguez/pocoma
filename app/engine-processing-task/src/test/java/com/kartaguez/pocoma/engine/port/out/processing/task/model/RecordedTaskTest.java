package com.kartaguez.pocoma.engine.port.out.processing.task.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class RecordedTaskTest {

	@Test
	void keepsAnOpaquePayloadWithoutProcessingMetadata() {
		RecordedTask task = task(1);

		assertEquals("{\"targetVersion\":1}", task.serializedPayload());
		assertEquals(1, task.targetVersion());
	}

	@Test
	void rejectsInvalidTargetVersionAndPayload() {
		RecordedTask valid = task(1);
		assertThrows(IllegalArgumentException.class, () -> new RecordedTask(
				valid.taskId(), valid.pipeline(), valid.potId(), 0, valid.createdAt(),
				valid.taskType(), valid.serializedPayload(), valid.traceId()));
		assertThrows(IllegalArgumentException.class, () -> new RecordedTask(
				valid.taskId(), valid.pipeline(), valid.potId(), 1, valid.createdAt(),
				valid.taskType(), " ", valid.traceId()));
	}

	private static RecordedTask task(long version) {
		return new RecordedTask(UUID.randomUUID(),
				new PipelineDefinition(PipelineId.of("balances"), 1),
				PotId.of(UUID.randomUUID()), version, Instant.now(),
				"COMPUTE_BALANCES_FOR_VERSION", "{\"targetVersion\":" + version + "}", Optional.empty());
	}
}
