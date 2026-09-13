package com.kartaguez.pocoma.engine.port.out.query;

import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;

public interface ProjectionReadinessQueryPort {

	/**
	 * Returns the highest actually ready business version at or below the inclusive bound.
	 * No continuity between ready versions is implied. The generation must not be null and the
	 * inclusive bound must be positive.
	 */
	OptionalLong findHighestReadyBusinessVersionAtOrBelow(
			ProjectionGenerationIdentity generation,
			long upperBoundInclusive);

	/** Returns the derived status of the non-null exact projection identity. */
	ProjectionStatus statusAt(ProjectionIdentity identity);
}
