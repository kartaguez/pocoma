package com.kartaguez.pocoma.pipeline.pot;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactDescriptor;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;
import com.kartaguez.pocoma.engine.read.projection.HistoricalPotReconstructionException;
import com.kartaguez.pocoma.engine.read.projection.ProjectionFailureService;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationResult;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationService;
import com.kartaguez.pocoma.engine.read.projection.ReconstructPotProjectionService;
import com.kartaguez.pocoma.engine.taskexecution.model.BusinessObjectVersion;
import com.kartaguez.pocoma.engine.taskexecution.model.ProducedArtifactReference;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;

public final class ExecutePotProjectionTaskHandler implements TaskExecutionHandler<ProjectPotTask> {

	private final PipelineDefinition pipeline;
	private final ReconstructPotProjectionService reconstruct;
	private final ProjectionMaterializationService<PotProjection> materialize;
	private final ProjectionFailureService failures;
	private final Clock clock;

	public ExecutePotProjectionTaskHandler(
			PipelineDefinition pipeline,
			ReconstructPotProjectionService reconstruct,
			ProjectionMaterializationService<PotProjection> materialize,
			ProjectionFailureService failures,
			Clock clock) {
		this.pipeline = Objects.requireNonNull(pipeline);
		this.reconstruct = Objects.requireNonNull(reconstruct);
		this.materialize = Objects.requireNonNull(materialize);
		this.failures = Objects.requireNonNull(failures);
		this.clock = Objects.requireNonNull(clock);
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
	public Class<ProjectPotTask> payloadType() {
		return ProjectPotTask.class;
	}

	@Override
	public TaskExecutionReport execute(ProjectPotTask task) {
		var identity = new ProjectionIdentity(
				new ProjectionGenerationIdentity(PotProjectionPipeline.TYPE, pipeline, task.potId()),
				task.potVersion());
		var input = new BusinessObjectVersion(
				"POT",
				task.potId().value().toString(),
				task.potVersion());

		try {
			var projection = reconstruct.reconstruct(identity);
			var result = materialize.materialize(identity, projection);
			if (result instanceof ProjectionMaterializationResult.Created created) {
				return success(input, created.descriptor());
			}
			if (result instanceof ProjectionMaterializationResult.AlreadySatisfied satisfied) {
				return success(input, satisfied.descriptor());
			}
			if (result instanceof ProjectionMaterializationResult.DivergentDuplicate) {
				return rejected("PROJECTION_DIVERGENT_DUPLICATE", input);
			}
			if (result instanceof ProjectionMaterializationResult.AlreadyFailed) {
				return rejected("PROJECTION_ALREADY_FAILED", input);
			}
			return rejected("PROJECTION_NOT_APPLICABLE", input);
		} catch (HistoricalPotReconstructionException exception) {
			failures.record(identity, clock.instant(), exception.failureCode());
			return rejected(exception.failureCode(), input);
		}
	}

	private TaskExecutionReport success(
			BusinessObjectVersion input,
			ProjectionArtifactDescriptor descriptor) {
		var output = new ProducedArtifactReference(
				"POT_PROJECTION",
				PotProjectionPipeline.PROJECTION_TYPE,
				descriptor.artifactId().value().toString(),
				OptionalLong.of(pipeline.pipelineVersion()),
				Optional.of(input),
				descriptor.createdAt());
		return new TaskExecutionReport.Succeeded(List.of(input), List.of(output));
	}

	private static TaskExecutionReport rejected(String failureCode, BusinessObjectVersion input) {
		return new TaskExecutionReport.Rejected(failureCode, List.of(input), List.of());
	}
}
