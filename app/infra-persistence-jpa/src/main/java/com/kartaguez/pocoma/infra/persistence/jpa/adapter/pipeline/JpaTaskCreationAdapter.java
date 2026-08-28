package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineMaterializationStatus;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;

/** Transitional adapter using the materialization table as an idempotency registry. */
@Component
public class JpaTaskCreationAdapter implements TaskCreationPort {

	private final JpaPipelineMaterializationRepository materializationRepository;
	private final JpaPipelineTaskRepository taskRepository;

	public JpaTaskCreationAdapter(
			JpaPipelineMaterializationRepository materializationRepository,
			JpaPipelineTaskRepository taskRepository) {
		this.materializationRepository = Objects.requireNonNull(
				materializationRepository, "materializationRepository must not be null");
		this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
	}

	@Override
	@Transactional(noRollbackFor = DataIntegrityViolationException.class)
	public TaskCreationResult createIfAbsent(
			EventPipelineTaskCreation creation,
			List<TaskDescriptor> tasks) {
		Objects.requireNonNull(creation, "creation must not be null");
		List<TaskDescriptor> immutableTasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
		var eventId = creation.recordedEvent().eventId();
		var pipeline = creation.pipeline();
		if (materializationRepository.findByEventIdAndPipelineIdAndPipelineVersion(
				eventId, pipeline.pipelineId().value(), pipeline.pipelineVersion()).isPresent()) {
			return TaskCreationResult.alreadyCreated(creation, 0);
		}

		Instant now = Instant.now();
		try {
			JpaPipelineMaterializationEntity registryEntry = materializationRepository.saveAndFlush(
					new JpaPipelineMaterializationEntity(
							eventId,
							pipeline.pipelineId().value(),
							pipeline.pipelineVersion(),
							PipelineMaterializationStatus.MATERIALIZED,
							null,
							null,
							now));
			taskRepository.saveAll(immutableTasks.stream()
					.map(task -> new JpaPipelineTaskEntity(
							registryEntry.id(),
							eventId,
							pipeline.pipelineId().value(),
							pipeline.pipelineVersion(),
							task,
							now))
					.toList());
			return TaskCreationResult.created(creation, immutableTasks.size());
		}
		catch (DataIntegrityViolationException duplicate) {
			return TaskCreationResult.alreadyCreated(creation, 0);
		}
	}
}
