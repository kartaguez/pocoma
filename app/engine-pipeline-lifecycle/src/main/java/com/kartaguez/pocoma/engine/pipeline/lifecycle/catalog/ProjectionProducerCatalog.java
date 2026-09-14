package com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ProjectionProducerBinding;

public final class ProjectionProducerCatalog {
	private final Set<ProjectionProducerBinding> bindings;

	public ProjectionProducerCatalog(Collection<ProjectionProducerBinding> bindings) {
		requireNonNull(bindings, "bindings must not be null");
		var indexed = new HashSet<ProjectionProducerBinding>();
		for (var binding : bindings) {
			requireNonNull(binding, "binding must not be null");
			if (!indexed.add(binding)) {
				throw new IllegalArgumentException("Duplicate projection producer binding: " + binding);
			}
		}
		this.bindings = Set.copyOf(indexed);
	}

	public boolean produces(PipelineDefinition pipeline, ProjectionType projectionType) {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(projectionType, "projectionType must not be null");
		return bindings.contains(new ProjectionProducerBinding(projectionType, pipeline));
	}
}
