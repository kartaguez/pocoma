package com.kartaguez.pocoma.engine.port.out.query;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.legacy.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.legacy.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.legacy.ProjectionStatus;

public interface ProjectionReadinessQueryPort {

	/**
	 * Returns the highest terminal business version at or below the inclusive bound for exactly the
	 * supplied generation. Terminal means READY or FAILED. No continuity between terminal versions
	 * is implied, and no other pipeline generation may be used as fallback. The generation must not
	 * be null and the inclusive bound must be positive.
	 */
	Optional<TerminalProjectionState> findHighestTerminalAtOrBelow(
			ProjectionGenerationIdentity generation,
			long upperBoundInclusive);

	/** Returns the derived status of the non-null exact projection identity. */
	ProjectionStatus statusAt(ProjectionIdentity identity);
}
