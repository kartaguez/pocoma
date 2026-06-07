package com.kartaguez.pocoma.pipelinetask;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionStrategy;

public final class ComputeBalancesPipelineTaskExecutionStrategy implements PipelineTaskExecutionStrategy {

	public static final PipelineDefinition DEFINITION = new PipelineDefinition(PipelineId.of("balance-projection"), 1);
	public static final String TASK_TYPE = "COMPUTE_BALANCES_FOR_VERSION";

	private final ComputePotBalancesUseCase computePotBalancesUseCase;
	private final ObjectMapper objectMapper;

	public ComputeBalancesPipelineTaskExecutionStrategy(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ObjectMapper objectMapper) {
		this.computePotBalancesUseCase = Objects.requireNonNull(
				computePotBalancesUseCase,
				"computePotBalancesUseCase must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public PipelineDefinition definition() {
		return DEFINITION;
	}

	@Override
	public boolean supports(String taskType) {
		return TASK_TYPE.equals(taskType);
	}

	@Override
	public void execute(PipelineTask task) {
		BalanceTaskPayload payload = readPayload(task);
		computePotBalancesUseCase.computePotBalances(PotId.of(UUID.fromString(payload.potId())), payload.targetVersion());
	}

	private BalanceTaskPayload readPayload(PipelineTask task) {
		try {
			return objectMapper.readValue(task.taskPayload(), BalanceTaskPayload.class);
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Invalid balance pipeline task payload", exception);
		}
	}

	private record BalanceTaskPayload(String potId, long targetVersion) {
		private BalanceTaskPayload {
			Objects.requireNonNull(potId, "potId must not be null");
			if (potId.isBlank()) {
				throw new IllegalArgumentException("potId must not be blank");
			}
			if (targetVersion < 1) {
				throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
			}
		}
	}
}
