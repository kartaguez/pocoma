package com.kartaguez.pocoma.engine.service.pipeline.lifecycle;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.PipelineVersionLifecycleUseCase;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateMutationPort;

public final class PipelineVersionLifecycleService implements PipelineVersionLifecycleUseCase {
	private final PipelineDefinitionRegistry definitions;
	private final ProjectionProducerCatalog producers;
	private final PipelineLifecycleStateMutationPort persistence;
	private final Clock clock;

	public PipelineVersionLifecycleService(PipelineDefinitionRegistry definitions,
			ProjectionProducerCatalog producers, PipelineLifecycleStateMutationPort persistence, Clock clock) {
		this.definitions = requireNonNull(definitions, "definitions must not be null");
		this.producers = requireNonNull(producers, "producers must not be null");
		this.persistence = requireNonNull(persistence, "persistence must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override public void activate(PipelineDefinition pipeline) {
		definitions.require(requireNonNull(pipeline, "pipeline must not be null"));
		persistence.activate(pipeline, clock.instant());
	}

	@Override public void deactivate(PipelineDefinition pipeline) {
		definitions.require(requireNonNull(pipeline, "pipeline must not be null"));
		if (persistence.deactivateIfNotServing(pipeline)
				== PipelineLifecycleStateMutationPort.DeactivationResult.SERVING) {
			throw new PipelineServingDeactivationException(pipeline);
		}
	}

	@Override public void selectServing(ProjectionType projectionType, PipelineDefinition pipeline) {
		requireNonNull(projectionType, "projectionType must not be null");
		definitions.require(requireNonNull(pipeline, "pipeline must not be null"));
		if (!producers.produces(pipeline, projectionType)) {
			throw new ProjectionProducerMismatchException(projectionType, pipeline);
		}
		if (persistence.selectServingIfActive(new ServingSelection(projectionType, pipeline), clock.instant())
				== PipelineLifecycleStateMutationPort.ServingMutationResult.INACTIVE) {
			throw new InactiveServingPipelineException(pipeline);
		}
	}

	@Override public void clearServing(ProjectionType projectionType) {
		persistence.clearServing(requireNonNull(projectionType, "projectionType must not be null"));
	}
}
