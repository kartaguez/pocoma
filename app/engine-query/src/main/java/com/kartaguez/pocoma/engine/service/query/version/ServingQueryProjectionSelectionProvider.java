package com.kartaguez.pocoma.engine.service.query.version;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.ServingSelectionQuery;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryProjectionSelection;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryProjectionSelectionProvider;

public final class ServingQueryProjectionSelectionProvider implements QueryProjectionSelectionProvider {
	private final ServingSelectionQuery selections;
	private final PipelineDefinitionRegistry definitions;

	public ServingQueryProjectionSelectionProvider(
			ServingSelectionQuery selections, PipelineDefinitionRegistry definitions) {
		this.selections = requireNonNull(selections, "selections must not be null");
		this.definitions = requireNonNull(definitions, "definitions must not be null");
	}

	@Override
	public Optional<QueryProjectionSelection> findServingSelection(ProjectionType projectionType) {
		requireNonNull(projectionType, "projectionType must not be null");
		return selections.findServing(projectionType).map(selection -> new QueryProjectionSelection(
				selection.projectionType(), definitions.require(selection.servingPipeline())));
	}
}
