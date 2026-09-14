package com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle;

import java.time.Instant;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;

public interface PipelineLifecycleStateMutationPort {
	enum DeactivationResult { DEACTIVATED, ALREADY_INACTIVE, SERVING }
	enum ServingMutationResult { SELECTED, UNCHANGED, INACTIVE }

	void activate(PipelineDefinition pipeline, Instant activatedAt);
	DeactivationResult deactivateIfNotServing(PipelineDefinition pipeline);
	ServingMutationResult selectServingIfActive(ServingSelection selection, Instant selectedAt);
	void clearServing(ProjectionType projectionType);
}
