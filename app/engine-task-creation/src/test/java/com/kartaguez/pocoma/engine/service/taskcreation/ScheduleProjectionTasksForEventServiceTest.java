package com.kartaguez.pocoma.engine.service.taskcreation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.EventPipelineRelevance;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

class ScheduleProjectionTasksForEventServiceTest {
	private static final PipelineId READ = PipelineId.of("read-pot");
	private static final PipelineId AUDIT = PipelineId.of("audit-pot");

	@Test
	void applicabilitySelectsEveryGenerationOnlyAfterPipelineRelevanceWasComputedOnce() {
		var v1 = definition(READ, 1, VersionApplicability.between(1, 49));
		var v2 = definition(READ, 2, VersionApplicability.from(50));
		AtomicInteger relevanceCalls = new AtomicInteger();
		RecordingPort port = new RecordingPort();
		var service = service(List.of(v1, v2), List.of(relevance(READ, relevanceCalls, true)),
				List.of(binding(v1.identity()), binding(v2.identity())), port);

		service.schedule(event(40));
		assertEquals(List.of(v1.identity()), port.pipelines);
		assertEquals(1, relevanceCalls.get());

		port.pipelines.clear();
		relevanceCalls.set(0);
		service.schedule(event(73));
		assertEquals(List.of(v2.identity()), port.pipelines);
		assertEquals(1, relevanceCalls.get());
	}

	@Test
	void overlappingApplicableGenerationsAndMultipleRelevantPipelinesAllProduceTasks() {
		var v1 = definition(READ, 1, VersionApplicability.from(1));
		var v2 = definition(READ, 2, VersionApplicability.from(50));
		var audit = definition(AUDIT, 1, VersionApplicability.from(1));
		RecordingPort port = new RecordingPort();
		var service = service(List.of(v1, v2, audit),
				List.of(relevance(READ, new AtomicInteger(), true), relevance(AUDIT, new AtomicInteger(), true)),
				List.of(binding(v1.identity()), binding(v2.identity()), binding(audit.identity())), port);

		service.schedule(event(73));

		assertEquals(List.of(audit.identity(), v1.identity(), v2.identity()), port.pipelines);
	}

	@Test
	void irrelevantPipelineSuppressesAllItsGenerationsAndNoApplicableDefinitionProducesNothing() {
		var future = definition(READ, 1, VersionApplicability.from(100));
		RecordingPort port = new RecordingPort();
		service(List.of(future), List.of(relevance(READ, new AtomicInteger(), true)),
				List.of(binding(future.identity())), port).schedule(event(73));
		assertEquals(List.of(), port.pipelines);

		service(List.of(future), List.of(relevance(READ, new AtomicInteger(), false)),
				List.of(binding(future.identity())), port).schedule(event(100));
		assertEquals(List.of(), port.pipelines);
	}

	@Test
	void wiringRejectsMissingOrDuplicateFamilyRelevanceAndMissingExactBinding() {
		var v1 = definition(READ, 1, VersionApplicability.from(1));
		assertThrows(IllegalArgumentException.class, () -> service(List.of(v1), List.of(),
				List.of(binding(v1.identity())), new RecordingPort()));
		assertThrows(IllegalArgumentException.class, () -> new EventPipelineRelevanceRegistry(List.of(
				relevance(READ, new AtomicInteger(), true), relevance(READ, new AtomicInteger(), false))));
		assertThrows(MissingTaskCreationStrategyException.class, () -> service(List.of(v1),
				List.of(relevance(READ, new AtomicInteger(), true)), List.of(), new RecordingPort()));
		assertThrows(IllegalArgumentException.class, () -> new TaskCreationStrategyRegistry(List.of(
				binding(v1.identity()), binding(v1.identity()))));
	}

	private static ScheduleProjectionTasksForEventService service(List<PipelineVersionDefinition> definitions,
			List<EventPipelineRelevance> relevances, List<TaskCreationStrategy> strategies, TaskCreationPort port) {
		return new ScheduleProjectionTasksForEventService(new PipelineDefinitionRegistry(definitions),
				new EventPipelineRelevanceRegistry(relevances), new TaskCreationStrategyRegistry(strategies), port);
	}

	private static EventPipelineRelevance relevance(PipelineId pipelineId, AtomicInteger calls, boolean answer) {
		return new EventPipelineRelevance() {
			@Override public PipelineId pipelineId() { return pipelineId; }
			@Override public boolean supports(BusinessEvent event) { calls.incrementAndGet(); return answer; }
		};
	}

	private static TaskCreationStrategy binding(PipelineDefinition definition) {
		return new TaskCreationStrategy() {
			@Override public PipelineDefinition definition() { return definition; }
			@Override public boolean supports(BusinessEvent event) {
				throw new AssertionError("Generation binding must not decide Event relevance");
			}
			@Override public List<TaskDescriptor> createTasks(BusinessEvent event) {
				return List.of(new TaskDescriptor("PROJECT", definition.toString(), "{}",
						event.potId().value().toString(), event.version()));
			}
		};
	}

	private static PipelineVersionDefinition definition(PipelineId id, int version,
			VersionApplicability applicability) {
		return new PipelineVersionDefinition(new PipelineDefinition(id, version), applicability);
	}

	private static RecordedEvent<PotCreatedEvent> event(long version) {
		return new RecordedEvent<>(UUID.randomUUID(), new PotCreatedEvent(PotId.of(UUID.randomUUID()), version),
				Instant.parse("2026-09-08T12:00:00Z"), EventTraceMetadata.empty());
	}

	private static final class RecordingPort implements TaskCreationPort {
		private final List<PipelineDefinition> pipelines = new ArrayList<>();
		@Override public TaskCreationResult.Materialized createIfAbsent(EventPipelineTaskCreation creation,
				List<TaskDescriptor> tasks) {
			pipelines.add(creation.pipeline());
			return TaskCreationResult.created(creation, List.of());
		}
	}
}
