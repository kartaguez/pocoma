package com.kartaguez.pocoma.pipeline.balance;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.projection.balance.CalculatePotBalancesAtVersionUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;
import com.kartaguez.pocoma.engine.taskexecution.model.BusinessObjectVersion;
import com.kartaguez.pocoma.engine.taskexecution.model.ProducedArtifactReference;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionArtifact;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionIdentity;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionPort;

public final class ExecuteBalanceProjectionTaskHandler implements TaskExecutionHandler<ComputeBalancesTask> {
	private final PipelineDefinition pipeline;
	private final CalculatePotBalancesAtVersionUseCase calculate;
	private final BalanceProjectionPort projections;

	public ExecuteBalanceProjectionTaskHandler(PipelineDefinition pipeline,
			CalculatePotBalancesAtVersionUseCase calculate, BalanceProjectionPort projections) {
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.calculate = requireNonNull(calculate, "calculate must not be null");
		this.projections = requireNonNull(projections, "projections must not be null");
	}

	@Override public PipelineDefinition pipeline() { return pipeline; }
	@Override public String taskType() { return BalancePipeline.TASK_TYPE; }
	@Override public Class<ComputeBalancesTask> payloadType() { return ComputeBalancesTask.class; }

	@Override
	public TaskExecutionReport execute(ComputeBalancesTask task) {
		requireNonNull(task, "task must not be null");
		var balances = calculate.calculate(task.potId(), task.targetVersion());
		var identity = new BalanceProjectionIdentity(BalancePipeline.PROJECTION_TYPE, pipeline,
				task.potId(), task.targetVersion());
		var persisted = projections.createOrVerify(new BalanceProjectionArtifact(identity, balances.balances()));
		var input = new BusinessObjectVersion("POT", task.potId().value().toString(), task.targetVersion());
		var reference = persisted.reference();
		var artifact = new ProducedArtifactReference("BALANCE_PROJECTION", BalancePipeline.PROJECTION_TYPE,
				reference.projectionId().toString(), OptionalLong.of(pipeline.pipelineVersion()),
				Optional.of(input), reference.createdAt());
		return new TaskExecutionReport.Succeeded(List.of(input), List.of(artifact));
	}
}
