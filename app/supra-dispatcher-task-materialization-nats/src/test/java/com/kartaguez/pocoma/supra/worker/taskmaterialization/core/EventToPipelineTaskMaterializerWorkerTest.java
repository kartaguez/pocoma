package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineStrategy;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksCommand;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksUseCase;
import com.kartaguez.pocoma.engine.taskmaterialization.port.out.TaskMaterializationPort;
import com.kartaguez.pocoma.engine.taskmaterialization.service.MaterializeTasksService;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

class EventToPipelineTaskMaterializerWorkerTest {

	@Test
	void runOnceSelectsCandidatesForActivePipelinesAndMaterializesThem() {
		PipelineDefinition definition = new PipelineDefinition(PipelineId.of("test"), 1);
		TestStrategy strategy = new TestStrategy(definition);
		PipelineRegistry registry = new PipelineRegistry(List.of(strategy));
		RecordingMaterializationPort port = new RecordingMaterializationPort();
		RecordingEventSource source = new RecordingEventSource();
		EventPipelineMaterializationCandidate candidate = new EventPipelineMaterializationCandidate(event(), definition);
		source.candidates = List.of(candidate);
		Clock clock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneOffset.UTC);
		EventToPipelineTaskMaterializerSettings settings = new EventToPipelineTaskMaterializerSettings(
				true,
				"materializer-test",
				25,
				Duration.ofSeconds(30),
				Duration.ofSeconds(5),
				new ProjectionPartition(1, 3),
				true);
		EventToPipelineTaskMaterializerWorker worker = new EventToPipelineTaskMaterializerWorker(
				source,
				new MaterializeTasksService(registry, port),
				registry,
				settings,
				clock,
				WorkWakeBus.noop(),
				ignored -> true);

		int processed = worker.runOnce();

		assertEquals(1, processed);
		assertEquals(25, source.limit);
		assertEquals(new ProjectionPartition(1, 3), source.partition);
		assertEquals(Instant.parse("2026-06-07T11:59:55Z"), source.upperBound);
		assertEquals(List.of(new ConfiguredPipelineBinding(definition, List.of("*"), true)), source.activeBindings);
		assertEquals(List.of(candidate), port.materializedCandidates);
		assertEquals(List.of(new TaskDescriptor("ECHO", "event-" + candidate.event().id(), "{}", candidate.event().potId().value().toString())),
				port.materializedTasks.getFirst());
	}

	@Test
	void runOnceDoesNothingWhenThereAreNoActivePipelines() {
		RecordingMaterializationPort port = new RecordingMaterializationPort();
		RecordingEventSource source = new RecordingEventSource();
		PipelineRegistry registry = new PipelineRegistry(List.of());
		EventToPipelineTaskMaterializerWorker worker = new EventToPipelineTaskMaterializerWorker(
				source,
				new MaterializeTasksService(registry, port),
				registry,
				new EventToPipelineTaskMaterializerSettings(true, "materializer-test", 10, Duration.ofSeconds(30)));

		int processed = worker.runOnce();

		assertEquals(0, processed);
		assertEquals(0, source.findCalls);
	}

	private static BusinessEventEnvelope event() {
		PotId potId = PotId.of(UUID.randomUUID());
		return new BusinessEventEnvelope(
				UUID.randomUUID(),
				"PotCreatedEvent",
				potId,
				potId.value(),
				1,
				"{}",
				null,
				null,
				Instant.now());
	}

	private static final class TestStrategy implements PipelineStrategy {
		private final PipelineDefinition definition;

		private TestStrategy(PipelineDefinition definition) {
			this.definition = definition;
		}

		@Override
		public PipelineDefinition definition() {
			return definition;
		}

		@Override
		public boolean supports(BusinessEventEnvelope event) {
			return true;
		}

		@Override
		public List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event) {
			return List.of(new TaskDescriptor(
					"ECHO",
					"event-" + event.id(),
					"{}",
					event.potId().value().toString()));
		}
	}

	private static final class RecordingEventSource implements MaterializableEventSource {
		private List<EventPipelineMaterializationCandidate> candidates = List.of();
		private int findCalls;
		private int limit;
		private ProjectionPartition partition;
		private Instant upperBound;
		private List<ConfiguredPipelineBinding> activeBindings = List.of();

		@Override
		public List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
				int limit,
				ProjectionPartition partition,
				Instant upperBound,
				List<ConfiguredPipelineBinding> activeBindings) {
			this.findCalls++;
			this.limit = limit;
			this.partition = partition;
			this.upperBound = upperBound;
			this.activeBindings = List.copyOf(activeBindings);
			return candidates;
		}

		@Override
		public long countUnmaterialized(List<ConfiguredPipelineBinding> activeBindings) {
			return 0;
		}
	}

	private static final class RecordingMaterializationPort implements TaskMaterializationPort {
		private final List<EventPipelineMaterializationCandidate> materializedCandidates = new ArrayList<>();
		private final List<List<TaskDescriptor>> materializedTasks = new ArrayList<>();

		@Override
		public MaterializationResult materialize(
				EventPipelineMaterializationCandidate candidate,
				List<TaskDescriptor> tasks) {
			materializedCandidates.add(candidate);
			materializedTasks.add(List.copyOf(tasks));
			return MaterializationResult.materialized(candidate);
		}

		@Override
		public MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate) {
			return MaterializationResult.skipped(candidate);
		}

		@Override
		public MaterializationResult markFailed(
				EventPipelineMaterializationCandidate candidate,
				String failureKind,
				RuntimeException error) {
			return MaterializationResult.failed(candidate);
		}

	}
}
