package com.kartaguez.pocoma.engine.taskmaterialization.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;

public final class PipelineRegistry {

	private final Map<PipelineDefinition, PipelineStrategy> strategies;
	private final List<ConfiguredPipelineBinding> bindings;

	public PipelineRegistry(Collection<? extends PipelineStrategy> strategies) {
		this(strategies, strategies.stream()
				.map(strategy -> new ConfiguredPipelineBinding(strategy.definition(), List.of("*"), true))
				.toList());
	}

	public PipelineRegistry(
			Collection<? extends PipelineStrategy> strategies,
			Collection<ConfiguredPipelineBinding> bindings) {
		Objects.requireNonNull(strategies, "strategies must not be null");
		Objects.requireNonNull(bindings, "bindings must not be null");
		this.strategies = strategies.stream()
				.collect(Collectors.toUnmodifiableMap(
						PipelineStrategy::definition,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException(
									"Duplicate pipeline strategy " + left.definition());
						}));
		this.bindings = List.copyOf(bindings);
		validateBindings(this.bindings, this.strategies.keySet());
	}

	public List<PipelineDefinition> activePipelines() {
		return bindings.stream()
				.filter(ConfiguredPipelineBinding::enabled)
				.map(ConfiguredPipelineBinding::definition)
				.distinct()
				.toList();
	}

	public List<ConfiguredPipelineBinding> activeBindings() {
		return bindings.stream()
				.filter(ConfiguredPipelineBinding::enabled)
				.toList();
	}

	public Optional<PipelineStrategy> find(PipelineDefinition definition) {
		Objects.requireNonNull(definition, "definition must not be null");
		return Optional.ofNullable(strategies.get(definition));
	}

	private static void validateBindings(
			List<ConfiguredPipelineBinding> bindings,
			Set<PipelineDefinition> knownDefinitions) {
		for (ConfiguredPipelineBinding binding : bindings) {
			if (binding.enabled() && !knownDefinitions.contains(binding.definition())) {
				throw new IllegalArgumentException(
						"No pipeline strategy registered for " + binding.definition());
			}
		}
	}
}
