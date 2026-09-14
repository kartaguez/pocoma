package com.kartaguez.pocoma.engine.service.pipeline.lifecycle;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateSnapshotPort;

public final class PipelineLifecycleIntegrityService {
	private final PipelineDefinitionRegistry definitions;
	private final ProjectionProducerCatalog producers;
	private final PipelineLifecycleStateSnapshotPort persistence;

	public PipelineLifecycleIntegrityService(PipelineDefinitionRegistry definitions,
			ProjectionProducerCatalog producers, PipelineLifecycleStateSnapshotPort persistence) {
		this.definitions = requireNonNull(definitions, "definitions must not be null");
		this.producers = requireNonNull(producers, "producers must not be null");
		this.persistence = requireNonNull(persistence, "persistence must not be null");
	}

	public void validate() {
		var snapshot = persistence.loadSnapshot();
		for (var activation : snapshot.activations()) definitions.require(activation);
		for (var selection : snapshot.servingSelections()) {
			definitions.require(selection.servingPipeline());
			if (!snapshot.activations().contains(selection.servingPipeline())) {
				throw new IllegalStateException("Serving selection has no activation: " + selection);
			}
			if (!producers.produces(selection.servingPipeline(), selection.projectionType())) {
				throw new ProjectionProducerMismatchException(
						selection.projectionType(), selection.servingPipeline());
			}
		}
	}
}
