package com.kartaguez.pocoma.engine.service.taskexecution;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;

public final class RecordedTaskExecutionMapperRegistry {
	private final Map<Key, RecordedTaskExecutionMapper<?>> mappers;

	public RecordedTaskExecutionMapperRegistry(Collection<? extends RecordedTaskExecutionMapper<?>> mappers) {
		this.mappers = requireNonNull(mappers, "mappers must not be null").stream()
				.collect(Collectors.toUnmodifiableMap(
						mapper -> new Key(mapper.pipeline(), mapper.taskType()), Function.identity(),
						(left, right) -> { throw new IllegalArgumentException("Duplicate RecordedTask mapper"); }));
	}

	public Optional<RecordedTaskExecutionMapper<?>> find(PipelineDefinition pipeline, String taskType) {
		return Optional.ofNullable(mappers.get(new Key(requireNonNull(pipeline), requireNonNull(taskType))));
	}

	private record Key(PipelineDefinition pipeline, String taskType) {}
}
