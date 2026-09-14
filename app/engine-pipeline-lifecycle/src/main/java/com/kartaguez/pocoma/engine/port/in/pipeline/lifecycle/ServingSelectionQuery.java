package com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;

public interface ServingSelectionQuery {
	Optional<ServingSelection> findServing(ProjectionType projectionType);
}
