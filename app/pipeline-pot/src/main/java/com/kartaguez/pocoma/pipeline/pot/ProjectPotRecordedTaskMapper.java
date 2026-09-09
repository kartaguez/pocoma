package com.kartaguez.pocoma.pipeline.pot;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure;

public final class ProjectPotRecordedTaskMapper implements RecordedTaskExecutionMapper<ProjectPotTask> {

	private final PipelineDefinition pipeline;
	private final ObjectMapper mapper;

	public ProjectPotRecordedTaskMapper(PipelineDefinition pipeline, ObjectMapper mapper) {
		this.pipeline = requireNonNull(pipeline);
		this.mapper = requireNonNull(mapper);
	}

	@Override
	public PipelineDefinition pipeline() {
		return pipeline;
	}

	@Override
	public String taskType() {
		return PotProjectionPipeline.TASK_TYPE;
	}

	@Override
	public ExecuteTaskInput<ProjectPotTask> map(RecordedTask task) {
		try {
			validateTaskBinding(task);

			var payload = mapper.readValue(task.serializedPayload(), Payload.class);
			var payloadPipeline = new PipelineDefinition(
					PipelineId.of(payload.pipelineId()),
					payload.pipelineVersion());
			var payloadPotId = PotId.of(UUID.fromString(payload.potId()));
			validatePayload(task, payload, payloadPipeline, payloadPotId);

			var projectPotTask = new ProjectPotTask(
					payloadPipeline,
					payloadPotId,
					payload.potVersion());
			return new ExecuteTaskInput<>(pipeline, taskType(), projectPotTask);
		} catch (InvalidPotTaskException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidPotTaskException("Invalid Pot task payload", exception);
		}
	}

	private void validateTaskBinding(RecordedTask task) {
		if (!pipeline.equals(task.pipeline()) || !taskType().equals(task.taskType())) {
			throw new InvalidPotTaskException("Task binding mismatch");
		}
	}

	private void validatePayload(
			RecordedTask task,
			Payload payload,
			PipelineDefinition payloadPipeline,
			PotId payloadPotId) {
		if (!pipeline.equals(payloadPipeline)
				|| !payloadPotId.equals(task.potId())
				|| payload.potVersion() != task.targetVersion()) {
			throw new InvalidPotTaskException("Task payload mismatch");
		}
	}

	private record Payload(String pipelineId, int pipelineVersion, String potId, long potVersion) {
	}

	public static final class InvalidPotTaskException extends RuntimeException
			implements NonRetryableTaskTechnicalFailure {

		public InvalidPotTaskException(String message) {
			super(message);
		}

		public InvalidPotTaskException(String message, Throwable cause) {
			super(message, cause);
		}

		@Override
		public String failureCode() {
			return "INVALID_TASK_PAYLOAD";
		}

		@Override
		public String failureCategory() {
			return "INVALID_TASK_PAYLOAD";
		}
	}
}
