package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PipelineRegistry {

	private final Map<PipelineDefinition, PipelineStrategy> strategies;

	public PipelineRegistry(Collection<? extends PipelineStrategy> strategies) {
		Objects.requireNonNull(strategies, "strategies must not be null");
		this.strategies = strategies.stream()
				.collect(Collectors.toUnmodifiableMap(
						PipelineStrategy::definition,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException(
									"Duplicate pipeline strategy " + left.definition());
						}));
	}

	public List<PipelineDefinition> activePipelines() {
		return List.copyOf(strategies.keySet());
	}

	public Optional<PipelineStrategy> find(PipelineDefinition definition) {
		Objects.requireNonNull(definition, "definition must not be null");
		return Optional.ofNullable(strategies.get(definition));
	}
}
