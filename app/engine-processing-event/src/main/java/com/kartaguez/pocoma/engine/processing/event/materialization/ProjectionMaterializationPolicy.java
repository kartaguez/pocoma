package com.kartaguez.pocoma.engine.processing.event.materialization;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

/** Exhaustive immutable decision table from known Event types to due Projection types. */
public final class ProjectionMaterializationPolicy {
	private final Map<EventType, Set<ProjectionType>> materializations;

	public ProjectionMaterializationPolicy(Set<EventType> knownEventTypes,
			Map<EventType, Set<ProjectionType>> materializations) {
		Set<EventType> known = Set.copyOf(requireNonNull(knownEventTypes, "knownEventTypes must not be null"));
		requireNonNull(materializations, "materializations must not be null");

		var copied = new LinkedHashMap<EventType, Set<ProjectionType>>();
		for (var entry : materializations.entrySet()) {
			EventType eventType = requireNonNull(entry.getKey(), "eventType must not be null");
			Set<ProjectionType> projectionTypes = Set.copyOf(requireNonNull(entry.getValue(),
					"projectionTypes must not be null"));
			copied.put(eventType, projectionTypes);
		}

		var missing = new LinkedHashSet<>(known);
		missing.removeAll(copied.keySet());
		var unknown = new LinkedHashSet<>(copied.keySet());
		unknown.removeAll(known);
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw new IllegalArgumentException("materializations must declare exactly the known EventTypes; missing="
					+ missing + ", unknown=" + unknown);
		}
		this.materializations = Map.copyOf(copied);
	}

	public Map<EventType, Set<ProjectionType>> materializations() {
		return materializations;
	}

	public Map<EventType, Set<ProjectionType>> materializationsFor(Set<ProjectionType> servedProjectionTypes) {
		Set<ProjectionType> served = Set.copyOf(requireNonNull(servedProjectionTypes,
				"servedProjectionTypes must not be null"));
		var filtered = new LinkedHashMap<EventType, Set<ProjectionType>>();
		for (var entry : materializations.entrySet()) {
			Set<ProjectionType> intersection = entry.getValue().stream()
					.filter(served::contains)
					.collect(Collectors.toUnmodifiableSet());
			if (!intersection.isEmpty()) filtered.put(entry.getKey(), intersection);
		}
		return Map.copyOf(filtered);
	}
}
