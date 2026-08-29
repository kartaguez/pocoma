package com.kartaguez.pocoma.supra.worker.task.mapping;

import static java.util.Objects.requireNonNull;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

public final class RecordedTaskExecutionMapperRegistry {

	private final Map<RecordedTaskMapperKey, RecordedTaskExecutionMapper<?>> mappers;

	public RecordedTaskExecutionMapperRegistry(
			Collection<? extends RecordedTaskExecutionMapper<?>> availableMappers) {
		requireNonNull(availableMappers, "availableMappers must not be null");
		Map<RecordedTaskMapperKey, RecordedTaskExecutionMapper<?>> indexed = new HashMap<>();
		for (RecordedTaskExecutionMapper<?> mapper : availableMappers) {
			requireNonNull(mapper, "mapper must not be null");
			RecordedTaskMapperKey key = new RecordedTaskMapperKey(mapper.pipeline(), mapper.taskType());
			if (indexed.putIfAbsent(key, mapper) != null) {
				throw new IllegalArgumentException("Duplicate recorded-task mapper for " + key);
			}
		}
		this.mappers = Map.copyOf(indexed);
	}

	public ExecuteTaskInput<? extends TaskPayload> map(RecordedTask task) {
		requireNonNull(task, "task must not be null");
		RecordedTaskExecutionMapper<?> mapper = mappers.get(new RecordedTaskMapperKey(task.pipeline(), task.taskType()));
		if (mapper == null) {
			throw new RecordedTaskMappingException(MISSING_TASK_MAPPER,
					"No recorded-task mapper is registered for the pipeline and task type");
		}
		ExecuteTaskInput<?> input = requireNonNull(mapper.map(task), "mapper result must not be null");
		if (!task.pipeline().equals(input.pipeline())) {
			throw new RecordedTaskMappingException(INCONSISTENT_MAPPED_PIPELINE,
					"Mapped task pipeline does not match the durable task");
		}
		if (!task.taskType().equals(input.taskType())) {
			throw new RecordedTaskMappingException(INCONSISTENT_MAPPED_TASK_TYPE,
					"Mapped task type does not match the durable task");
		}
		if (!mapper.payloadType().isInstance(input.task())) {
			throw new RecordedTaskMappingException(INCONSISTENT_MAPPED_PAYLOAD_TYPE,
					"Mapped payload type does not match the mapper contract");
		}
		return input;
	}
}
