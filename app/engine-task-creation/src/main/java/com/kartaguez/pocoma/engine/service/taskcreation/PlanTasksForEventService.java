package com.kartaguez.pocoma.engine.service.taskcreation;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationPlan;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.PlanTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.PlanTasksForEventUseCase;

public final class PlanTasksForEventService implements PlanTasksForEventUseCase {

	private final TaskCreationStrategyRegistry strategyRegistry;

	public PlanTasksForEventService(TaskCreationStrategyRegistry strategyRegistry) {
		this.strategyRegistry = requireNonNull(strategyRegistry, "strategyRegistry must not be null");
	}

	@Override
	public TaskCreationPlan planTasks(PlanTasksForEventInput input) {
		requireNonNull(input, "input must not be null");
		TaskCreationStrategy strategy = strategyRegistry.find(input.pipeline())
				.orElseThrow(() -> new MissingTaskCreationStrategyException(input.pipeline()));
		List<TaskDescriptor> tasks = strategy.supports(input.event())
				? List.copyOf(requireNonNull(strategy.createTasks(input.event()), "created tasks must not be null"))
				: List.of();
		return new TaskCreationPlan(input.event(), input.pipeline(), tasks);
	}
}
