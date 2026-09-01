package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.PotPartitioner;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationOutcome;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@DataJpaTest
@Import({ JpaPipelineMaterializationAdapter.class, JpaTaskCreationAdapter.class,
		JpaMaterializableEventSourceAdapter.class, JpaBusinessEventOutboxAdapter.class })
class JpaPipelineMaterializationAdapterTest {

	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("test-pipeline"), 1);

	@Autowired
	private JpaPipelineMaterializationAdapter adapter;

	@Autowired
	private JpaTaskCreationAdapter taskCreationAdapter;

	@Autowired
	private JpaMaterializableEventSourceAdapter eventSource;

	@Autowired
	private JpaBusinessEventOutboxAdapter outboxAdapter;

	@Autowired
	private JpaPipelineMaterializationRepository materializationRepository;

	@Autowired
	private JpaPipelineTaskRepository taskRepository;

	@Test
	void materializesCandidateIntoRegistryAndTasks() {
		outboxAdapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		EventPipelineMaterializationCandidate candidate = candidates(binding("PotCreatedEvent")).getFirst();

		MaterializationResult result = adapter.materialize(candidate, List.of(task(candidate)));

		assertEquals(MaterializationOutcome.MATERIALIZED, result.outcome());
		assertEquals(1, result.taskCount());
		assertEquals(1, materializationRepository.count());
		assertEquals(1, taskRepository.count());
		assertEquals(0, eventSource.countUnmaterialized(List.of(binding("PotCreatedEvent"))));
	}

	@Test
	void repeatedMaterializationDoesNotDuplicateRows() {
		outboxAdapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		EventPipelineMaterializationCandidate candidate = candidates(binding("PotCreatedEvent")).getFirst();

		adapter.materialize(candidate, List.of(task(candidate)));
		MaterializationResult repeated = adapter.materialize(candidate, List.of(task(candidate)));

		assertEquals(MaterializationOutcome.ALREADY_MATERIALIZED, repeated.outcome());
		assertEquals(1, materializationRepository.count());
		assertEquals(1, taskRepository.count());
	}

	@Test
	void typedTaskCreationRecordsAnEmptyPlanIdempotently() {
		UUID eventId = UUID.randomUUID();
		var recordedEvent = new RecordedEvent<>(
				eventId,
				new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1),
				Instant.now(),
				EventTraceMetadata.empty());
		var creation = new EventPipelineTaskCreation(recordedEvent, PIPELINE);

		assertEquals(TaskCreationOutcome.CREATED,
				taskCreationAdapter.createIfAbsent(creation, List.of()).outcome());
		assertEquals(TaskCreationOutcome.ALREADY_CREATED,
				taskCreationAdapter.createIfAbsent(creation, List.of()).outcome());
		assertEquals(1, materializationRepository.count());
		assertEquals(0, taskRepository.count());
	}

	@Test
	void filtersCandidatesByEventType() {
		outboxAdapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));

		assertEquals(0, candidates(binding("ExpenseCreatedEvent")).size());
		assertEquals(1, candidates(binding("PotCreatedEvent")).size());
	}

	@Test
	void filtersCandidatesByPartition() {
		PotId segment0PotId = potIdForSegment(0, 2);
		PotId segment1PotId = potIdForSegment(1, 2);
		outboxAdapter.append(new PotCreatedEvent(segment0PotId, 1));
		outboxAdapter.append(new PotCreatedEvent(segment1PotId, 1));

		List<EventPipelineMaterializationCandidate> segment0Candidates = eventSource.findUnmaterializedEventPipelinePairs(
				10,
				new ProjectionPartition(0, 2),
				Instant.now().plusSeconds(1),
				List.of(binding("PotCreatedEvent")));

		assertEquals(List.of(segment0PotId), segment0Candidates.stream()
				.map(candidate -> candidate.event().potId())
				.toList());
	}

	@Test
	void skippedAndFailedCandidatesWriteRegistryRows() {
		outboxAdapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		outboxAdapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 2));
		List<EventPipelineMaterializationCandidate> candidates = candidates(binding("PotCreatedEvent"));

		assertEquals(MaterializationOutcome.SKIPPED, adapter.markSkipped(candidates.get(0)).outcome());
		assertEquals(MaterializationOutcome.FAILED, adapter.markFailed(
				candidates.get(1),
				"IllegalStateException",
				new IllegalStateException("boom")).outcome());

		assertEquals(2, materializationRepository.count());
		assertEquals(0, taskRepository.count());
	}

	private List<EventPipelineMaterializationCandidate> candidates(ConfiguredPipelineBinding binding) {
		return eventSource.findUnmaterializedEventPipelinePairs(
				10,
				ProjectionPartition.single(),
				Instant.now().plusSeconds(1),
				List.of(binding));
	}

	private static ConfiguredPipelineBinding binding(String eventType) {
		return new ConfiguredPipelineBinding(PIPELINE, List.of(eventType), true);
	}

	private static TaskDescriptor task(EventPipelineMaterializationCandidate candidate) {
		return new TaskDescriptor(
				"ECHO",
				"event-" + candidate.event().id(),
				"{}",
				candidate.event().potId().value().toString(), candidate.event().version());
	}

	private static PotId potIdForSegment(int segmentIndex, int segmentCount) {
		for (int index = 0; index < 1_000; index++) {
			PotId potId = PotId.of(UUID.nameUUIDFromBytes(
					("pipeline-pot-" + segmentIndex + "-" + index).getBytes(StandardCharsets.UTF_8)));
			if (PotPartitioner.segmentOf(potId, segmentCount) == segmentIndex) {
				return potId;
			}
		}
		throw new IllegalStateException("No potId found for segment " + segmentIndex);
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
