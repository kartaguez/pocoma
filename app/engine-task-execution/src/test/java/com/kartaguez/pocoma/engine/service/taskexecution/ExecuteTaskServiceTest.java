package com.kartaguez.pocoma.engine.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.exception.InvalidTaskPayloadTypeException;
import com.kartaguez.pocoma.engine.exception.MissingTaskExecutionHandlerException;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;

class ExecuteTaskServiceTest {

	@Test
	void routesTypedTaskToExactPipelineVersionAndTaskType() {
		PipelineDefinition balancesV1 = pipeline("balances", 1);
		PipelineDefinition balancesV2 = pipeline("balances", 2);
		RecordingHandler firstType = new RecordingHandler(balancesV1, "first");
		RecordingHandler secondType = new RecordingHandler(balancesV1, "second");
		RecordingHandler secondVersion = new RecordingHandler(balancesV2, "first");
		var service = service(firstType, secondType, secondVersion);
		TestPayload payload = new TestPayload("work");

		service.executeTask(new ExecuteTaskInput<>(balancesV1, "second", payload));

		assertEquals(List.of(), firstType.executed);
		assertEquals(List.of(payload), secondType.executed);
		assertEquals(List.of(), secondVersion.executed);
	}

	@Test
	void rejectsDuplicateHandlerKey() {
		PipelineDefinition pipeline = pipeline("balances", 1);

		assertThrows(IllegalArgumentException.class, () -> new TaskExecutionHandlerRegistry(List.of(
				new RecordingHandler(pipeline, "compute"),
				new RecordingHandler(pipeline, "compute"))));
	}

	@Test
	void reportsMissingHandlerWithoutConsultingWorkerBindings() {
		var service = service(new RecordingHandler(pipeline("balances", 1), "compute"));

		assertThrows(MissingTaskExecutionHandlerException.class,
				() -> service.executeTask(new ExecuteTaskInput<>(
						pipeline("notifications", 1), "notify", new TestPayload("work"))));
	}

	@Test
	void executesDirectlyWithoutAnyWorkerBinding() {
		PipelineDefinition pipeline = pipeline("balances", 1);
		RecordingHandler handler = new RecordingHandler(pipeline, "compute");
		TestPayload payload = new TestPayload("direct work");

		service(handler).executeTask(new ExecuteTaskInput<>(pipeline, "compute", payload));

		assertEquals(List.of(payload), handler.executed);
	}

	@Test
	void rejectsPayloadOfUnexpectedType() {
		PipelineDefinition pipeline = pipeline("balances", 1);
		var service = service(new RecordingHandler(pipeline, "compute"));

		assertThrows(InvalidTaskPayloadTypeException.class,
				() -> service.executeTask(new ExecuteTaskInput<>(pipeline, "compute", new OtherPayload())));
	}

	@Test
	void propagatesHandlerFailureUnchanged() {
		PipelineDefinition pipeline = pipeline("balances", 1);
		RecordingHandler handler = new RecordingHandler(pipeline, "compute");
		RuntimeException failure = new IllegalStateException("boom");
		handler.failure = failure;

		assertSame(failure, assertThrows(RuntimeException.class,
				() -> service(handler).executeTask(
						new ExecuteTaskInput<>(pipeline, "compute", new TestPayload("work")))));
	}

	private static ExecuteTaskService service(TaskExecutionHandler<?>... handlers) {
		return new ExecuteTaskService(new TaskExecutionHandlerRegistry(List.of(handlers)));
	}

	private static PipelineDefinition pipeline(String id, int version) {
		return new PipelineDefinition(PipelineId.of(id), version);
	}

	private record TestPayload(String value) implements TaskPayload {
	}

	private record OtherPayload() implements TaskPayload {
	}

	private static final class RecordingHandler implements TaskExecutionHandler<TestPayload> {
		private final PipelineDefinition pipeline;
		private final String taskType;
		private final List<TestPayload> executed = new ArrayList<>();
		private RuntimeException failure;

		private RecordingHandler(PipelineDefinition pipeline, String taskType) {
			this.pipeline = pipeline;
			this.taskType = taskType;
		}

		@Override
		public PipelineDefinition pipeline() {
			return pipeline;
		}

		@Override
		public String taskType() {
			return taskType;
		}

		@Override
		public Class<TestPayload> payloadType() {
			return TestPayload.class;
		}

		@Override
		public TaskExecutionReport execute(TestPayload task) {
			if (failure != null) {
				throw failure;
			}
			executed.add(task);
			return new TaskExecutionReport.Succeeded(List.of(), List.of());
		}
	}
}
