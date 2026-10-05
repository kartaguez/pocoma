package com.kartaguez.pocoma.engine.produce.projectiontask.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.produce.projectiontask.ordering.ProjectionMaterializationOrderingKey;

/** Metadata-only discovery of Event to Projection materializations that are not yet DONE. */
public interface ProjectionMaterializationDiscoveryPort {

	List<ProjectionMaterializationCandidate> findCandidates(
			Map<EventType, Set<ProjectionType>> routes,
			int segmentIndex,
			int segmentCount,
			Optional<ProjectionMaterializationOrderingKey> afterExclusive,
			int limit);
}
