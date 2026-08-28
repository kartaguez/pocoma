package com.kartaguez.pocoma.engine.service.taskexecution;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;

public final class TaskExecutionHandlerRegistry {

	private final Map<HandlerKey, TaskExecutionHandler<?>> handlers;

	public TaskExecutionHandlerRegistry(Collection<? extends TaskExecutionHandler<?>> handlers) {
		requireNonNull(handlers, "handlers must not be null");
		this.handlers = handlers.stream()
				.map(handler -> requireNonNull(handler, "handler must not be null"))
				.collect(Collectors.toUnmodifiableMap(
						handler -> HandlerKey.from(handler.pipeline(), handler.taskType()),
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException(
									"Duplicate task execution handler for " + left.pipeline() + " / " + left.taskType());
						}));
	}

	public Optional<TaskExecutionHandler<?>> find(PipelineDefinition pipeline, String taskType) {
		return Optional.ofNullable(handlers.get(HandlerKey.from(pipeline, taskType)));
	}

	private record HandlerKey(PipelineDefinition pipeline, String taskType) {

		private static HandlerKey from(PipelineDefinition pipeline, String taskType) {
			requireNonNull(pipeline, "pipeline must not be null");
			requireNonNull(taskType, "taskType must not be null");
			if (taskType.isBlank()) {
				throw new IllegalArgumentException("taskType must not be blank");
			}
			return new HandlerKey(pipeline, taskType);
		}
	}
}
