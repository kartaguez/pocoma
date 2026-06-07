package com.kartaguez.pocoma.engine.taskexecution.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.task.ConfiguredTaskExecutionBinding;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

public final class PipelineTaskExecutionRegistry {

	private final Map<PipelineDefinition, PipelineTaskExecutionStrategy> strategies;
	private final List<ConfiguredTaskExecutionBinding> bindings;

	public PipelineTaskExecutionRegistry(Collection<? extends PipelineTaskExecutionStrategy> strategies) {
		this(strategies, strategies.stream()
				.map(strategy -> new ConfiguredTaskExecutionBinding(strategy.definition(), List.of("*"), true))
				.toList());
	}

	public PipelineTaskExecutionRegistry(
			Collection<? extends PipelineTaskExecutionStrategy> strategies,
			Collection<ConfiguredTaskExecutionBinding> bindings) {
		Objects.requireNonNull(strategies, "strategies must not be null");
		Objects.requireNonNull(bindings, "bindings must not be null");
		this.strategies = strategies.stream()
				.collect(Collectors.toUnmodifiableMap(
						PipelineTaskExecutionStrategy::definition,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException("Duplicate pipeline task strategy " + left.definition());
						}));
		this.bindings = List.copyOf(bindings);
		validateBindings(this.bindings, this.strategies.keySet());
	}

	public List<ConfiguredTaskExecutionBinding> activeBindings() {
		return bindings.stream()
				.filter(ConfiguredTaskExecutionBinding::enabled)
				.toList();
	}

	public Optional<PipelineTaskExecutionStrategy> find(PipelineTask task) {
		Objects.requireNonNull(task, "task must not be null");
		boolean enabled = bindings.stream()
				.anyMatch(binding -> binding.matches(task.pipeline(), task.taskType()));
		if (!enabled) {
			return Optional.empty();
		}
		return Optional.ofNullable(strategies.get(task.pipeline()))
				.filter(strategy -> strategy.supports(task.taskType()));
	}

	private static void validateBindings(
			List<ConfiguredTaskExecutionBinding> bindings,
			Set<PipelineDefinition> knownDefinitions) {
		for (ConfiguredTaskExecutionBinding binding : bindings) {
			if (binding.enabled() && !knownDefinitions.contains(binding.definition())) {
				throw new IllegalArgumentException("No pipeline task strategy registered for " + binding.definition());
			}
		}
	}
}
