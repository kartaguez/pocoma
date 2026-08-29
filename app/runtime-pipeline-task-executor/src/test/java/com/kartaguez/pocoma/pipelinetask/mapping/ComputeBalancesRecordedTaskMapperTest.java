package com.kartaguez.pocoma.pipelinetask.mapping;

import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INCONSISTENT_MAPPED_PIPELINE;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INCONSISTENT_MAPPED_TASK_TYPE;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INVALID_TASK_PAYLOAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.pipelinetask.ComputeBalancesTask;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException;

class ComputeBalancesRecordedTaskMapperTest {

	private final ComputeBalancesRecordedTaskMapper mapper =
			new ComputeBalancesRecordedTaskMapper(new ObjectMapper());

	@Test
	void mapsRecordedTaskAndPreservesTypedRouting() {
		PotId potId = PotId.of(UUID.randomUUID());
		RecordedTask task = task(potId, 5, json(potId, 5));

		var input = new RecordedTaskExecutionMapperRegistry(java.util.List.of(mapper)).map(task);

		assertEquals(ComputeBalancesTask.PIPELINE, input.pipeline());
		assertEquals(ComputeBalancesTask.TASK_TYPE, input.taskType());
		assertEquals(new ComputeBalancesTask(potId, 5), input.task());
	}

	@Test
	void rejectsInvalidJsonAndFieldsWithSafeError() {
		PotId potId = PotId.of(UUID.randomUUID());
		for (String payload : java.util.List.of(
				"not-json", "{}", "{\"potId\":\"\",\"targetVersion\":1}",
				"{\"potId\":\"not-a-uuid\",\"targetVersion\":1}", json(potId, 0))) {
			RecordedTaskMappingException failure = assertThrows(RecordedTaskMappingException.class,
					() -> mapper.map(task(potId, 1, payload)));
			assertEquals(INVALID_TASK_PAYLOAD, failure.code());
			assertEquals("Invalid balance task payload", failure.getMessage());
			assertFalse(failure.getMessage().contains(potId.value().toString()));
			assertFalse(failure.getMessage().contains(payload));
		}
	}

	@Test
	void rejectsPayloadMetadataDivergence() {
		PotId durablePot = PotId.of(UUID.randomUUID());
		PotId payloadPot = PotId.of(UUID.randomUUID());
		assertCode(INVALID_TASK_PAYLOAD, task(durablePot, 4, json(payloadPot, 4)));
		assertCode(INVALID_TASK_PAYLOAD, task(durablePot, 4, json(durablePot, 5)));
	}

	@Test
	void rejectsWrongPipelineAndTaskTypeWithWiringCodes() {
		PotId potId = PotId.of(UUID.randomUUID());
		RecordedTask base = task(potId, 1, json(potId, 1));
		assertCode(INCONSISTENT_MAPPED_PIPELINE, new RecordedTask(base.taskId(),
				new PipelineDefinition(PipelineId.of("other"), 1), potId, 1, base.createdAt(),
				base.taskType(), base.serializedPayload(), Optional.empty()));
		assertCode(INCONSISTENT_MAPPED_TASK_TYPE, new RecordedTask(base.taskId(), base.pipeline(), potId, 1,
				base.createdAt(), "OTHER", base.serializedPayload(), Optional.empty()));
	}

	private void assertCode(String code, RecordedTask task) {
		assertEquals(code, assertThrows(RecordedTaskMappingException.class, () -> mapper.map(task)).code());
	}

	private static RecordedTask task(PotId potId, long version, String payload) {
		return new RecordedTask(UUID.randomUUID(), ComputeBalancesTask.PIPELINE, potId, version, Instant.EPOCH,
				ComputeBalancesTask.TASK_TYPE, payload, Optional.empty());
	}

	private static String json(PotId potId, long version) {
		return "{\"potId\":\"" + potId.value() + "\",\"targetVersion\":" + version + "}";
	}
}
