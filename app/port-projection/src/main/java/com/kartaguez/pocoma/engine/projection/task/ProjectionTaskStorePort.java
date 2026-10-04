package com.kartaguez.pocoma.engine.projection.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public interface ProjectionTaskStorePort {
	ProjectionTask ensure(ProjectionKey key, Instant createdAt);

	List<ProjectionTaskCandidate> findCandidates(
			Set<ProjectionType> projectionTypes,
			int segmentIndex,
			int segmentCount,
			Optional<Instant> afterCreatedAt,
			Optional<UUID> afterRowId,
			int limit);
}
