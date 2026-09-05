package com.kartaguez.pocoma.pipeline.balance;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

public final class ComputeBalancesRecordedTaskMapper implements RecordedTaskExecutionMapper<ComputeBalancesTask> {
	private final PipelineDefinition pipeline;
	private final ObjectMapper mapper;

	public ComputeBalancesRecordedTaskMapper(PipelineDefinition pipeline, ObjectMapper mapper) {
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.mapper = requireNonNull(mapper, "mapper must not be null");
	}

	@Override public PipelineDefinition pipeline() { return pipeline; }
	@Override public String taskType() { return BalancePipeline.TASK_TYPE; }

	@Override
	public ExecuteTaskInput<ComputeBalancesTask> map(RecordedTask task) {
		requireNonNull(task, "task must not be null");
		if (!pipeline.equals(task.pipeline()) || !taskType().equals(task.taskType())) {
			throw new InvalidBalanceTaskException("Task binding does not match the Balance mapper");
		}
		try {
			SerializedPayload payload = mapper.readValue(task.serializedPayload(), SerializedPayload.class);
			PotId potId = PotId.of(UUID.fromString(payload.potId()));
			if (!potId.equals(task.potId()) || payload.targetVersion() != task.targetVersion()) {
				throw new InvalidBalanceTaskException("Task payload does not match its durable identity");
			}
			return new ExecuteTaskInput<>(pipeline, taskType(), new ComputeBalancesTask(potId, payload.targetVersion()));
		}
		catch (InvalidBalanceTaskException exception) { throw exception; }
		catch (Exception exception) { throw new InvalidBalanceTaskException("Invalid Balance task payload", exception); }
	}

	private record SerializedPayload(String potId, long targetVersion) {}

	public static final class InvalidBalanceTaskException extends RuntimeException
			implements com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure {
		public InvalidBalanceTaskException(String message) { super(message); }
		public InvalidBalanceTaskException(String message, Throwable cause) { super(message, cause); }
		@Override public String failureCode() { return "INVALID_TASK_PAYLOAD"; }
		@Override public String failureCategory() { return "INVALID_TASK_PAYLOAD"; }
	}
}
