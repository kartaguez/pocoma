package com.kartaguez.pocoma.engine.port.in.query.version;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.ProjectionType;

public interface QueryProjectionSelectionProvider {
	Optional<QueryProjectionSelection> findServingSelection(ProjectionType projectionType);
}
