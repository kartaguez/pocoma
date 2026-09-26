package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSearch;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSource;

/** Passive, metadata-only candidate source for Event to ProjectionTask materialization. */
public final class ProjectionMaterializationConsumptionSource
		implements FencedConsumptionCandidateSource<ProjectionMaterializationCandidate> {
	private final Map<EventType, Set<ProjectionType>> routes;
	private final WorkerSegment segment;
	private final ProjectionMaterializationDiscoveryPort discovery;

	public ProjectionMaterializationConsumptionSource(
			Map<EventType, Set<ProjectionType>> routes,
			WorkerSegment segment,
			ProjectionMaterializationDiscoveryPort discovery) {
		this.routes = copyRoutes(routes);
		this.segment = requireNonNull(segment, "segment must not be null");
		this.discovery = requireNonNull(discovery, "discovery must not be null");
	}

	@Override
	public FencedConsumptionCandidateSearch<ProjectionMaterializationCandidate> openSearch() {
		return new Search();
	}

	private final class Search implements FencedConsumptionCandidateSearch<ProjectionMaterializationCandidate> {
		private Optional<ProjectionMaterializationOrderingKey> cursor = Optional.empty();

		@Override
		public java.util.List<ProjectionMaterializationCandidate> nextPage(int limit) {
			if (limit < 1) throw new IllegalArgumentException("limit must be positive");
			var page = discovery.findCandidates(routes, segment, cursor, limit);
			if (!page.isEmpty()) cursor = Optional.of(page.getLast().orderingKey());
			return page;
		}
	}

	private static Map<EventType, Set<ProjectionType>> copyRoutes(
			Map<EventType, Set<ProjectionType>> suppliedRoutes) {
		requireNonNull(suppliedRoutes, "routes must not be null");
		var copy = new LinkedHashMap<EventType, Set<ProjectionType>>();
		for (var entry : suppliedRoutes.entrySet()) {
			var eventType = requireNonNull(entry.getKey(), "route eventType must not be null");
			var projectionTypes = Set.copyOf(requireNonNull(
					entry.getValue(), "route projectionTypes must not be null"));
			copy.put(eventType, projectionTypes);
		}
		return Map.copyOf(copy);
	}
}
