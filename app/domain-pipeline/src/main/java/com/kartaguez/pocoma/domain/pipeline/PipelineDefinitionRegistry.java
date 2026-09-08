package com.kartaguez.pocoma.domain.pipeline;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class PipelineDefinitionRegistry {
	private final Map<PipelineDefinition, PipelineVersionDefinition> definitions;

	public PipelineDefinitionRegistry(Collection<PipelineVersionDefinition> definitions) {
		requireNonNull(definitions, "definitions must not be null");
		var indexed = new LinkedHashMap<PipelineDefinition, PipelineVersionDefinition>();
		for (var definition : definitions) {
			requireNonNull(definition, "definition must not be null");
			if (indexed.putIfAbsent(definition.identity(), definition) != null) {
				throw new IllegalArgumentException("Duplicate pipeline definition: "
						+ definition.identity().pipelineId().value() + "@" + definition.identity().pipelineVersion());
			}
		}
		this.definitions = Map.copyOf(indexed);
	}

	public PipelineVersionDefinition require(PipelineDefinition identity) {
		requireNonNull(identity, "identity must not be null");
		var definition = definitions.get(identity);
		if (definition == null) throw new UnknownPipelineDefinitionException(identity);
		return definition;
	}

	public Collection<PipelineVersionDefinition> all() {
		return definitions.values();
	}
}
