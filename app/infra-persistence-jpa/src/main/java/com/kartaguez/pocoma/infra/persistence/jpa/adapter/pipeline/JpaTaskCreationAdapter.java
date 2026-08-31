package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.port.in.taskcreation.result.PersistedTaskReference;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult.Materialized;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;

/** Task writes join the consumption execution transaction and can never commit independently. */
@Component
public class JpaTaskCreationAdapter implements TaskCreationPort {
	private final JpaPipelineMaterializationRepository materializations;
	private final JpaPipelineTaskRepository tasks;
	private final Clock clock;

	@Autowired
	public JpaTaskCreationAdapter(JpaPipelineMaterializationRepository materializations,
			JpaPipelineTaskRepository tasks) {
		this(materializations, tasks, Clock.systemUTC());
	}

	JpaTaskCreationAdapter(JpaPipelineMaterializationRepository materializations,
			JpaPipelineTaskRepository tasks, Clock clock) {
		this.materializations = requireNonNull(materializations, "materializations must not be null");
		this.tasks = requireNonNull(tasks, "tasks must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Materialized createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> descriptors) {
		requireNonNull(creation, "creation must not be null");
		List<TaskDescriptor> immutable = List.copyOf(requireNonNull(descriptors, "tasks must not be null"));
		var pipeline = creation.pipeline();
		var eventId = creation.recordedEvent().eventId();
		var existing = materializations.findByEventIdAndPipelineIdAndPipelineVersion(
				eventId, pipeline.pipelineId().value(), pipeline.pipelineVersion());
		if (existing.isPresent()) {
			return TaskCreationResult.alreadyCreated(creation, references(existing.orElseThrow().id()));
		}

		Instant now = clock.instant();
		UUID materializationId = UUID.randomUUID();
		int inserted = materializations.insertMaterializedIfAbsent(materializationId, eventId,
				pipeline.pipelineId().value(), pipeline.pipelineVersion(), now);
		if (inserted == 0) {
			JpaPipelineMaterializationEntity winner = materializations
					.findByEventIdAndPipelineIdAndPipelineVersion(
							eventId, pipeline.pipelineId().value(), pipeline.pipelineVersion())
					.orElseThrow(() -> new IllegalStateException("materialization winner is not visible"));
			return TaskCreationResult.alreadyCreated(creation, references(winner.id()));
		}
		List<JpaPipelineTaskEntity> persisted = tasks.saveAllAndFlush(immutable.stream()
				.map(task -> new JpaPipelineTaskEntity(materializationId, eventId,
						pipeline.pipelineId().value(), pipeline.pipelineVersion(), task, now))
				.toList());
		return TaskCreationResult.created(creation, persisted.stream().map(JpaTaskCreationAdapter::reference).toList());
	}

	private List<PersistedTaskReference> references(UUID materializationId) {
		return tasks.findByMaterializationIdOrderById(materializationId).stream()
				.map(JpaTaskCreationAdapter::reference).toList();
	}

	private static PersistedTaskReference reference(JpaPipelineTaskEntity task) {
		return new PersistedTaskReference(task.id(), task.taskType(), task.createdAt());
	}
}
