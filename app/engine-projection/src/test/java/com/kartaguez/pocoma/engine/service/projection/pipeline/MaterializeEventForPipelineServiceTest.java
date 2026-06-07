package com.kartaguez.pocoma.engine.service.projection.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.pipeline.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.model.pipeline.MaterializationOutcome;
import com.kartaguez.pocoma.engine.model.pipeline.MaterializationResult;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineRegistry;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineStrategy;
import com.kartaguez.pocoma.engine.model.pipeline.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.out.persistence.pipeline.PipelineMaterializationPort;

class MaterializeEventForPipelineServiceTest {

	@Test
	void materializesSupportedEvent() {
		PipelineDefinition definition = definition();
		TaskDescriptor task = new TaskDescriptor("ECHO", "task-1", "{}", "partition");
		TestStrategy strategy = new TestStrategy(definition, true, List.of(task));
		RecordingMaterializationPort port = new RecordingMaterializationPort();
		MaterializeEventForPipelineService service = service(strategy, port);
		EventPipelineMaterializationCandidate candidate = candidate(definition);

		MaterializationResult result = service.materialize(candidate);

		assertEquals(MaterializationOutcome.MATERIALIZED, result.outcome());
		assertEquals(candidate, port.materializedCandidate);
		assertEquals(List.of(task), port.tasks);
	}

	@Test
	void marksSkippedWhenStrategyDoesNotSupportEvent() {
		PipelineDefinition definition = definition();
		TestStrategy strategy = new TestStrategy(definition, false, List.of());
		RecordingMaterializationPort port = new RecordingMaterializationPort();
		MaterializeEventForPipelineService service = service(strategy, port);
		EventPipelineMaterializationCandidate candidate = candidate(definition);

		MaterializationResult result = service.materialize(candidate);

		assertEquals(MaterializationOutcome.SKIPPED, result.outcome());
		assertEquals(candidate, port.skippedCandidate);
	}

	@Test
	void marksFailedWhenStrategyFails() {
		PipelineDefinition definition = definition();
		TestStrategy strategy = new TestStrategy(definition, true, List.of());
		strategy.failure = new IllegalStateException("boom");
		RecordingMaterializationPort port = new RecordingMaterializationPort();
		MaterializeEventForPipelineService service = service(strategy, port);
		EventPipelineMaterializationCandidate candidate = candidate(definition);

		MaterializationResult result = service.materialize(candidate);

		assertEquals(MaterializationOutcome.FAILED, result.outcome());
		assertEquals(candidate, port.failedCandidate);
		assertEquals("IllegalStateException", port.failureKind);
	}

	private static MaterializeEventForPipelineService service(
			PipelineStrategy strategy,
			RecordingMaterializationPort port) {
		return new MaterializeEventForPipelineService(new PipelineRegistry(List.of(strategy)), port);
	}

	private static PipelineDefinition definition() {
		return new PipelineDefinition(PipelineId.of("test-pipeline"), 1);
	}

	private static EventPipelineMaterializationCandidate candidate(PipelineDefinition definition) {
		return new EventPipelineMaterializationCandidate(event(), definition);
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
		private final boolean supports;
		private final List<TaskDescriptor> tasks;
		private RuntimeException failure;

		private TestStrategy(PipelineDefinition definition, boolean supports, List<TaskDescriptor> tasks) {
			this.definition = definition;
			this.supports = supports;
			this.tasks = tasks;
		}

		@Override
		public PipelineDefinition definition() {
			return definition;
		}

		@Override
		public boolean supports(BusinessEventEnvelope event) {
			return supports;
		}

		@Override
		public List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event) {
			if (failure != null) {
				throw failure;
			}
			return tasks;
		}
	}

	private static final class RecordingMaterializationPort implements PipelineMaterializationPort {
		private EventPipelineMaterializationCandidate materializedCandidate;
		private EventPipelineMaterializationCandidate skippedCandidate;
		private EventPipelineMaterializationCandidate failedCandidate;
		private String failureKind;
		private List<TaskDescriptor> tasks = new ArrayList<>();

		@Override
		public List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
				int limit,
				ProjectionPartition partition,
				Instant upperBound,
				List<PipelineDefinition> activePipelines) {
			return List.of();
		}

		@Override
		public MaterializationResult materialize(
				EventPipelineMaterializationCandidate candidate,
				List<TaskDescriptor> tasks) {
			this.materializedCandidate = candidate;
			this.tasks = List.copyOf(tasks);
			return MaterializationResult.materialized(candidate);
		}

		@Override
		public MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate) {
			this.skippedCandidate = candidate;
			return MaterializationResult.skipped(candidate);
		}

		@Override
		public MaterializationResult markFailed(
				EventPipelineMaterializationCandidate candidate,
				String failureKind,
				RuntimeException error) {
			this.failedCandidate = candidate;
			this.failureKind = failureKind;
			return MaterializationResult.failed(candidate);
		}

		@Override
		public long countUnmaterialized(List<PipelineDefinition> activePipelines) {
			return 0;
		}
	}
}
