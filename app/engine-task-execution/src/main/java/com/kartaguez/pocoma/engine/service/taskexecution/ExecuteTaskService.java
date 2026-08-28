package com.kartaguez.pocoma.engine.service.taskexecution;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.exception.InvalidTaskPayloadTypeException;
import com.kartaguez.pocoma.engine.exception.MissingTaskExecutionHandlerException;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;

public final class ExecuteTaskService implements ExecuteTaskUseCase {

	private final TaskExecutionHandlerRegistry handlerRegistry;

	public ExecuteTaskService(TaskExecutionHandlerRegistry handlerRegistry) {
		this.handlerRegistry = requireNonNull(handlerRegistry, "handlerRegistry must not be null");
	}

	@Override
	public void executeTask(ExecuteTaskInput<? extends TaskPayload> input) {
		requireNonNull(input, "input must not be null");
		TaskExecutionHandler<?> handler = handlerRegistry.find(input.pipeline(), input.taskType())
				.orElseThrow(() -> new MissingTaskExecutionHandlerException(input.pipeline(), input.taskType()));
		if (!handler.payloadType().isInstance(input.task())) {
			throw new InvalidTaskPayloadTypeException(
					input.pipeline(), input.taskType(), handler.payloadType(), input.task().getClass());
		}
		execute(handler, input.task());
	}

	private static <T extends TaskPayload> void execute(
			TaskExecutionHandler<T> handler,
			TaskPayload task) {
		handler.execute(handler.payloadType().cast(task));
	}
}
