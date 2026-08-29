package com.kartaguez.pocoma.pipelinetask.mapping;

import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INCONSISTENT_MAPPED_PIPELINE;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INCONSISTENT_MAPPED_TASK_TYPE;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INVALID_TASK_PAYLOAD;
import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.pipelinetask.ComputeBalancesTask;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException;

/** Runtime JSON adapter shared temporarily by the target and legacy Balance paths. */
public final class ComputeBalancesRecordedTaskMapper
		implements RecordedTaskExecutionMapper<ComputeBalancesTask> {

	private static final String INVALID_PAYLOAD_MESSAGE = "Invalid balance task payload";

	private final ObjectMapper objectMapper;

	public ComputeBalancesRecordedTaskMapper(ObjectMapper objectMapper) {
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public PipelineDefinition pipeline() {
		return ComputeBalancesTask.PIPELINE;
	}

	@Override
	public String taskType() {
		return ComputeBalancesTask.TASK_TYPE;
	}

	@Override
	public Class<ComputeBalancesTask> payloadType() {
		return ComputeBalancesTask.class;
	}

	@Override
	public ExecuteTaskInput<ComputeBalancesTask> map(RecordedTask task) {
		requireNonNull(task, "task must not be null");
		return mapSerialized(task.pipeline(), task.taskType(), task.serializedPayload(), task.potId(),
				task.targetVersion());
	}

	public ExecuteTaskInput<ComputeBalancesTask> mapLegacy(LegacyPipelineTask task) {
		requireNonNull(task, "task must not be null");
		ComputeBalancesSerializedPayload payload = readPayload(task.taskPayload());
		return mapDecoded(task.pipeline(), task.taskType(), payload, task.potId(), null);
	}

	public ExecuteTaskInput<ComputeBalancesTask> mapSerialized(
			PipelineDefinition persistedPipeline,
			String persistedTaskType,
			String serializedPayload,
			PotId persistedPotId,
			long persistedTargetVersion) {
		return mapDecoded(persistedPipeline, persistedTaskType, readPayload(serializedPayload), persistedPotId,
				persistedTargetVersion);
	}

	private ExecuteTaskInput<ComputeBalancesTask> mapDecoded(
			PipelineDefinition persistedPipeline,
			String persistedTaskType,
			ComputeBalancesSerializedPayload payload,
			PotId persistedPotId,
			Long persistedTargetVersion) {
		requireNonNull(persistedPipeline, "persistedPipeline must not be null");
		requireNonNull(persistedTaskType, "persistedTaskType must not be null");
		requireNonNull(persistedPotId, "persistedPotId must not be null");
		if (!pipeline().equals(persistedPipeline)) {
			throw new RecordedTaskMappingException(INCONSISTENT_MAPPED_PIPELINE,
					"Balance task pipeline does not match the mapper");
		}
		if (!taskType().equals(persistedTaskType)) {
			throw new RecordedTaskMappingException(INCONSISTENT_MAPPED_TASK_TYPE,
					"Balance task type does not match the mapper");
		}
		PotId payloadPotId = parsePotId(payload.potId());
		if (!persistedPotId.equals(payloadPotId)
				|| persistedTargetVersion != null && persistedTargetVersion.longValue() != payload.targetVersion()) {
			throw invalidPayload(null);
		}
		return new ExecuteTaskInput<>(pipeline(), taskType(),
				new ComputeBalancesTask(payloadPotId, validateTargetVersion(payload.targetVersion())));
	}

	private ComputeBalancesSerializedPayload readPayload(String serializedPayload) {
		try {
			return requireNonNull(objectMapper.readValue(
					requireNonNull(serializedPayload, "serializedPayload must not be null"),
					ComputeBalancesSerializedPayload.class), "decoded payload must not be null");
		}
		catch (RecordedTaskMappingException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw invalidPayload(exception);
		}
	}

	private static PotId parsePotId(String value) {
		try {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("missing potId");
			}
			return PotId.of(UUID.fromString(value));
		}
		catch (RuntimeException exception) {
			throw invalidPayload(exception);
		}
	}

	private static long validateTargetVersion(long targetVersion) {
		if (targetVersion < 1) {
			throw invalidPayload(null);
		}
		return targetVersion;
	}

	private static RecordedTaskMappingException invalidPayload(Throwable cause) {
		return new RecordedTaskMappingException(INVALID_TASK_PAYLOAD, INVALID_PAYLOAD_MESSAGE, cause);
	}
}
