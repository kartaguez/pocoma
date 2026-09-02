package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationOutcome;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@DataJpaTest
@Import({ JpaPipelineMaterializationAdapter.class, JpaTaskCreationAdapter.class })
class JpaPipelineMaterializationAdapterTest {

	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("test-pipeline"), 1);

	@Autowired
	private JpaPipelineMaterializationAdapter adapter;

	@Autowired
	private JpaTaskCreationAdapter taskCreationAdapter;

	@Autowired
	private JpaPipelineMaterializationRepository materializationRepository;

	@Autowired
	private JpaPipelineTaskRepository taskRepository;

	@Test
	void materializesCandidateIntoRegistryAndTasks() {
		EventPipelineMaterializationCandidate candidate = candidate(1);

		MaterializationResult result = adapter.materialize(candidate, List.of(task(candidate)));

		assertEquals(MaterializationOutcome.MATERIALIZED, result.outcome());
		assertEquals(1, result.taskCount());
		assertEquals(1, materializationRepository.count());
		assertEquals(1, taskRepository.count());
	}

	@Test
	void repeatedMaterializationDoesNotDuplicateRows() {
		EventPipelineMaterializationCandidate candidate = candidate(1);

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
	void skippedAndFailedCandidatesWriteRegistryRows() {
		List<EventPipelineMaterializationCandidate> candidates = List.of(candidate(1), candidate(2));

		assertEquals(MaterializationOutcome.SKIPPED, adapter.markSkipped(candidates.get(0)).outcome());
		assertEquals(MaterializationOutcome.FAILED, adapter.markFailed(
				candidates.get(1),
				"IllegalStateException",
				new IllegalStateException("boom")).outcome());

		assertEquals(2, materializationRepository.count());
		assertEquals(0, taskRepository.count());
	}

	private static EventPipelineMaterializationCandidate candidate(long version) {
		var event = new com.kartaguez.pocoma.engine.legacy.event.BusinessEventEnvelope(UUID.randomUUID(),
				"PotCreatedEvent", PotId.of(UUID.randomUUID()), UUID.randomUUID(), version, "{}",
				null, null, Instant.now());
		return new EventPipelineMaterializationCandidate(event, PIPELINE);
	}

	private static TaskDescriptor task(EventPipelineMaterializationCandidate candidate) {
		return new TaskDescriptor(
				"ECHO",
				"event-" + candidate.event().id(),
				"{}",
				candidate.event().potId().value().toString(), candidate.event().version());
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
