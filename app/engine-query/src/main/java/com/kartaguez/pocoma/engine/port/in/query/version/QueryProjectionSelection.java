package com.kartaguez.pocoma.engine.port.in.query.version;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

/**
 * The single business projection requested by a versioned query and the authoritative serving
 * pipeline generation selected for it, including for historical reads.
 */
public record QueryProjectionSelection(
		ProjectionType projectionType,
		PipelineVersionDefinition servingPipeline) {

	public QueryProjectionSelection {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(servingPipeline, "servingPipeline must not be null");
	}
}
