package com.kartaguez.pocoma.supra.worker.task.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

class RecordedTaskExecutionMapperRegistryTest {

	private static final PipelineDefinition V1 = new PipelineDefinition(PipelineId.of("balances"), 1);
	private static final PipelineDefinition V2 = new PipelineDefinition(PipelineId.of("balances"), 2);

	@Test
	void routesByPipelineVersionAndTaskType() {
		var registry = new RecordedTaskExecutionMapperRegistry(List.of(
				mapper(V1, "A", "v1-a"), mapper(V1, "B", "v1-b"), mapper(V2, "A", "v2-a")));

		assertEquals("v1-a", ((Payload) registry.map(task(V1, "A")).task()).value());
		assertEquals("v1-b", ((Payload) registry.map(task(V1, "B")).task()).value());
		assertEquals("v2-a", ((Payload) registry.map(task(V2, "A")).task()).value());
	}

	@Test
	void rejectsDuplicateAndMissingMappers() {
		assertThrows(IllegalArgumentException.class, () -> new RecordedTaskExecutionMapperRegistry(
				List.of(mapper(V1, "A", "one"), mapper(V1, "A", "two"))));

		RecordedTaskMappingException missing = assertThrows(RecordedTaskMappingException.class,
				() -> new RecordedTaskExecutionMapperRegistry(List.of()).map(task(V1, "A")));
		assertEquals(RecordedTaskMappingException.MISSING_TASK_MAPPER, missing.code());
	}

	@Test
	void validatesMappedPipelineTypeAndPayloadClass() {
		assertCode(RecordedTaskMappingException.INCONSISTENT_MAPPED_PIPELINE,
				new Mapper(V1, "A", Payload.class, ignored -> new ExecuteTaskInput<>(V2, "A", new Payload("x"))));
		assertCode(RecordedTaskMappingException.INCONSISTENT_MAPPED_TASK_TYPE,
				new Mapper(V1, "A", Payload.class, ignored -> new ExecuteTaskInput<>(V1, "B", new Payload("x"))));
		assertCode(RecordedTaskMappingException.INCONSISTENT_MAPPED_PAYLOAD_TYPE,
				new Mapper(V1, "A", OtherPayload.class,
						ignored -> new ExecuteTaskInput<>(V1, "A", new Payload("x"))));
	}

	private static void assertCode(String code, RecordedTaskExecutionMapper<?> mapper) {
		RecordedTaskMappingException exception = assertThrows(RecordedTaskMappingException.class,
				() -> new RecordedTaskExecutionMapperRegistry(List.of(mapper)).map(task(V1, "A")));
		assertEquals(code, exception.code());
	}

	private static RecordedTaskExecutionMapper<TaskPayload> mapper(
			PipelineDefinition pipeline, String taskType, String value) {
		return new Mapper(pipeline, taskType, Payload.class,
				ignored -> new ExecuteTaskInput<>(pipeline, taskType, new Payload(value)));
	}

	private static RecordedTask task(PipelineDefinition pipeline, String taskType) {
		return new RecordedTask(UUID.randomUUID(), pipeline, PotId.of(UUID.randomUUID()), 1,
				Instant.parse("2026-08-29T12:00:00Z"), taskType, "{}", Optional.empty());
	}

	private record Payload(String value) implements TaskPayload { }
	private record OtherPayload(String value) implements TaskPayload { }

	private static final class Mapper implements RecordedTaskExecutionMapper<TaskPayload> {
		private final PipelineDefinition pipeline;
		private final String taskType;
		private final Class<TaskPayload> payloadType;
		private final java.util.function.Function<RecordedTask, ExecuteTaskInput<? extends TaskPayload>> function;

		@SuppressWarnings("unchecked")
		private Mapper(PipelineDefinition pipeline, String taskType, Class<? extends TaskPayload> payloadType,
				java.util.function.Function<RecordedTask, ExecuteTaskInput<? extends TaskPayload>> function) {
			this.pipeline = pipeline;
			this.taskType = taskType;
			this.payloadType = (Class<TaskPayload>) payloadType;
			this.function = function;
		}

		@Override public PipelineDefinition pipeline() { return pipeline; }
		@Override public String taskType() { return taskType; }

		@Override
		public Class<TaskPayload> payloadType() {
			return payloadType;
		}

		@Override
		@SuppressWarnings("unchecked")
		public ExecuteTaskInput<TaskPayload> map(RecordedTask task) {
			return (ExecuteTaskInput<TaskPayload>) function.apply(task);
		}
	}
}
