package com.kartaguez.pocoma.engine.service.taskcreation;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

/** Plans every applicable generation before atomically persisting any Event-derived Task. */
public final class ScheduleProjectionTasksForEventService implements ScheduleProjectionTasksForEventUseCase {
	private static final Comparator<PipelineVersionDefinition> ORDER = Comparator
			.comparing((PipelineVersionDefinition value) -> value.identity().pipelineId().value())
			.thenComparingInt(value -> value.identity().pipelineVersion());

	private final PipelineDefinitionRegistry definitions;
	private final EventPipelineRelevanceRegistry relevances;
	private final TaskCreationStrategyRegistry strategies;
	private final TaskCreationPort persistence;

	public ScheduleProjectionTasksForEventService(PipelineDefinitionRegistry definitions,
			EventPipelineRelevanceRegistry relevances, TaskCreationStrategyRegistry strategies,
			TaskCreationPort persistence) {
		this.definitions = requireNonNull(definitions, "definitions must not be null");
		this.relevances = requireNonNull(relevances, "relevances must not be null");
		this.strategies = requireNonNull(strategies, "strategies must not be null");
		this.persistence = requireNonNull(persistence, "persistence must not be null");
		validateWiring();
	}

	@Override
	public EventTaskSchedulingResult schedule(RecordedEvent<? extends BusinessEvent> recordedEvent) {
		requireNonNull(recordedEvent, "recordedEvent must not be null");
		List<PlannedGeneration> plans = new ArrayList<>();
		try {
			Set<com.kartaguez.pocoma.domain.pipeline.PipelineId> relevantPipelines = definitions.all().stream()
					.map(definition -> definition.identity().pipelineId()).distinct()
					.filter(pipelineId -> relevances.find(pipelineId).orElseThrow().supports(recordedEvent.event()))
					.collect(Collectors.toUnmodifiableSet());
			definitions.all().stream().sorted(ORDER)
					.filter(definition -> relevantPipelines.contains(definition.identity().pipelineId()))
					.filter(definition -> definition.appliesTo(recordedEvent.event().version()))
					.forEach(definition -> plans.add(plan(recordedEvent.event(), definition)));
		}
		catch (TaskCreationRejectedException rejection) {
			return new EventTaskSchedulingResult.Rejected(recordedEvent.eventId(), rejection.rejectionCode());
		}

		List<TaskCreationResult.Materialized> results = plans.stream()
				.map(plan -> persistence.createIfAbsent(
						new EventPipelineTaskCreation(recordedEvent, plan.definition().identity()), plan.tasks()))
				.toList();
		return new EventTaskSchedulingResult.Scheduled(recordedEvent.eventId(), results);
	}

	private PlannedGeneration plan(BusinessEvent event, PipelineVersionDefinition definition) {
		TaskCreationStrategy strategy = strategies.find(definition.identity())
				.orElseThrow(() -> new MissingTaskCreationStrategyException(definition.identity()));
		List<TaskDescriptor> tasks = List.copyOf(requireNonNull(strategy.createTasks(event),
				"created tasks must not be null"));
		if (tasks.size() != 1) {
			throw new IllegalStateException("An applicable Event-derived generation must create exactly one Task: "
					+ definition.identity());
		}
		return new PlannedGeneration(definition, tasks);
	}

	private void validateWiring() {
		for (PipelineVersionDefinition definition : definitions.all()) {
			if (relevances.find(definition.identity().pipelineId()).isEmpty()) {
				throw new IllegalArgumentException("Missing Event relevance for pipeline "
						+ definition.identity().pipelineId().value());
			}
			if (strategies.find(definition.identity()).isEmpty()) {
				throw new MissingTaskCreationStrategyException(definition.identity());
			}
		}
	}

	private record PlannedGeneration(PipelineVersionDefinition definition, List<TaskDescriptor> tasks) {}
}
