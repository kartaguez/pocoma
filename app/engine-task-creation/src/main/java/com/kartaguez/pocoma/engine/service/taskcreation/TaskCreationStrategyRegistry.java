package com.kartaguez.pocoma.engine.service.taskcreation;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;

public final class TaskCreationStrategyRegistry {

	private final Map<PipelineDefinition, TaskCreationStrategy> strategies;

	public TaskCreationStrategyRegistry(Collection<? extends TaskCreationStrategy> strategies) {
		requireNonNull(strategies, "strategies must not be null");
		this.strategies = strategies.stream()
				.map(strategy -> requireNonNull(strategy, "strategy must not be null"))
				.collect(Collectors.toUnmodifiableMap(
						TaskCreationStrategy::definition,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException("Duplicate task-creation strategy " + left.definition());
						}));
	}

	public Optional<TaskCreationStrategy> find(PipelineDefinition definition) {
		return Optional.ofNullable(strategies.get(requireNonNull(definition, "definition must not be null")));
	}
}
