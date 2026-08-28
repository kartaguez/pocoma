package com.kartaguez.pocoma.engine.service.taskcreation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.PlanTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;

class TaskCreationServicesTest {

	private static final PipelineDefinition BALANCES_V1 = pipeline("balances", 1);
	private static final TaskDescriptor TASK = new TaskDescriptor("balance", "key", "{}", null);

	@Test
	void plansTasksDirectlyFromTypedBusinessEvent() {
		PotCreatedEvent event = event();
		var service = planner(new FixedStrategy(BALANCES_V1, true, List.of(TASK)));

		var plan = service.planTasks(new PlanTasksForEventInput(event, BALANCES_V1));

		assertSame(event, plan.event());
		assertEquals(List.of(TASK), plan.tasks());
	}

	@Test
	void uninterestedStrategyProducesNoTask() {
		var strategy = new FixedStrategy(BALANCES_V1, false, List.of(TASK));
		var plan = planner(strategy).planTasks(new PlanTasksForEventInput(event(), BALANCES_V1));

		assertEquals(List.of(), plan.tasks());
		assertEquals(0, strategy.invocationCount);
	}

	@Test
	void missingStrategyIsAnExplicitApplicationError() {
		var service = new PlanTasksForEventService(new TaskCreationStrategyRegistry(List.of()));

		assertThrows(MissingTaskCreationStrategyException.class,
				() -> service.planTasks(new PlanTasksForEventInput(event(), BALANCES_V1)));
	}

	@Test
	void strategyFailurePropagatesWithoutCallingPersistence() {
		RuntimeException failure = new RuntimeException("boom");
		TaskCreationStrategy strategy = new FixedStrategy(BALANCES_V1, true, List.of()) {
			@Override
			public List<TaskDescriptor> createTasks(BusinessEvent event) {
				throw failure;
			}
		};
		CountingPort port = new CountingPort();
		var service = new CreateTasksForEventService(planner(strategy), port);

		assertSame(failure, assertThrows(RuntimeException.class,
				() -> service.createTasks(new CreateTasksForEventInput(recordedEvent(), BALANCES_V1))));
		assertEquals(0, port.invocationCount);
	}

	@Test
	void durableCreationIsIdempotentIncludingAnEmptyPlan() {
		InMemoryIdempotentPort port = new InMemoryIdempotentPort();
		var service = new CreateTasksForEventService(
				planner(new FixedStrategy(BALANCES_V1, false, List.of(TASK))), port);
		var input = new CreateTasksForEventInput(recordedEvent(), BALANCES_V1);

		assertEquals(TaskCreationOutcome.CREATED, service.createTasks(input).outcome());
		assertEquals(TaskCreationOutcome.ALREADY_CREATED, service.createTasks(input).outcome());
		assertEquals(1, port.entries.size());
		assertEquals(List.of(), port.entries.values().iterator().next());
	}

	@Test
	void pipelineAndVersionHaveIndependentIdempotencyScopesForTheSameEvent() {
		RecordedEvent<PotCreatedEvent> recordedEvent = recordedEvent();
		InMemoryIdempotentPort port = new InMemoryIdempotentPort();
		var balancesV2 = pipeline("balances", 2);
		var notificationsV1 = pipeline("notifications", 1);
		var registry = new TaskCreationStrategyRegistry(List.of(
				new FixedStrategy(BALANCES_V1, true, List.of(TASK)),
				new FixedStrategy(balancesV2, true, List.of(TASK)),
				new FixedStrategy(notificationsV1, true, List.of(TASK))));
		var service = new CreateTasksForEventService(new PlanTasksForEventService(registry), port);

		assertEquals(TaskCreationOutcome.CREATED,
				service.createTasks(new CreateTasksForEventInput(recordedEvent, BALANCES_V1)).outcome());
		assertEquals(TaskCreationOutcome.CREATED,
				service.createTasks(new CreateTasksForEventInput(recordedEvent, balancesV2)).outcome());
		assertEquals(TaskCreationOutcome.CREATED,
				service.createTasks(new CreateTasksForEventInput(recordedEvent, notificationsV1)).outcome());
		assertEquals(3, port.entries.size());
	}

	private static PlanTasksForEventService planner(TaskCreationStrategy... strategies) {
		return new PlanTasksForEventService(new TaskCreationStrategyRegistry(List.of(strategies)));
	}

	private static PotCreatedEvent event() {
		return new PotCreatedEvent(PotId.of(UUID.randomUUID()), 3);
	}

	private static RecordedEvent<PotCreatedEvent> recordedEvent() {
		return new RecordedEvent<>(UUID.randomUUID(), event(), Instant.parse("2026-08-28T06:00:00Z"),
				EventTraceMetadata.empty());
	}

	private static PipelineDefinition pipeline(String id, int version) {
		return new PipelineDefinition(PipelineId.of(id), version);
	}

	private static class FixedStrategy implements TaskCreationStrategy {

		private final PipelineDefinition definition;
		private final boolean supported;
		private final List<TaskDescriptor> tasks;
		private int invocationCount;

		private FixedStrategy(PipelineDefinition definition, boolean supported, List<TaskDescriptor> tasks) {
			this.definition = definition;
			this.supported = supported;
			this.tasks = tasks;
		}

		@Override
		public PipelineDefinition definition() {
			return definition;
		}

		@Override
		public boolean supports(BusinessEvent event) {
			return supported;
		}

		@Override
		public List<TaskDescriptor> createTasks(BusinessEvent event) {
			invocationCount++;
			return tasks;
		}
	}

	private static final class CountingPort implements TaskCreationPort {
		private int invocationCount;

		@Override
		public TaskCreationResult createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> tasks) {
			invocationCount++;
			return TaskCreationResult.created(creation, tasks.size());
		}
	}

	private static final class InMemoryIdempotentPort implements TaskCreationPort {
		private final Map<String, List<TaskDescriptor>> entries = new HashMap<>();

		@Override
		public TaskCreationResult createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> tasks) {
			String key = creation.recordedEvent().eventId() + ":" + creation.pipeline().pipelineId().value()
					+ ":" + creation.pipeline().pipelineVersion();
			if (entries.containsKey(key)) {
				return TaskCreationResult.alreadyCreated(creation, 0);
			}
			entries.put(key, List.copyOf(tasks));
			return TaskCreationResult.created(creation, tasks.size());
		}
	}
}
