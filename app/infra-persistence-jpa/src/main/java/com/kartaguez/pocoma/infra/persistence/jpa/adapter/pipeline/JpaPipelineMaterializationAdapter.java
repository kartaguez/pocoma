package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineMaterializationStatus;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.engine.taskmaterialization.port.out.TaskMaterializationPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;

@Component
public class JpaPipelineMaterializationAdapter implements TaskMaterializationPort {

	private final JpaPipelineMaterializationRepository materializationRepository;
	private final JpaPipelineTaskRepository taskRepository;

	public JpaPipelineMaterializationAdapter(
			JpaPipelineMaterializationRepository materializationRepository,
			JpaPipelineTaskRepository taskRepository) {
		this.materializationRepository = Objects.requireNonNull(
				materializationRepository,
				"materializationRepository must not be null");
		this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
	}

	@Override
	@Transactional(noRollbackFor = DataIntegrityViolationException.class)
	public MaterializationResult materialize(
			EventPipelineMaterializationCandidate candidate,
			List<TaskDescriptor> tasks) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		Objects.requireNonNull(tasks, "tasks must not be null");
		if (alreadyMaterialized(candidate)) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
		Instant now = Instant.now();
		try {
			JpaPipelineMaterializationEntity materialization = materializationRepository.saveAndFlush(
					materialization(candidate, PipelineMaterializationStatus.MATERIALIZED, null, null, now));
			taskRepository.saveAll(tasks.stream()
					.map(task -> task(candidate, materialization.id(), task, now))
					.toList());
			taskRepository.flush();
			return MaterializationResult.materialized(candidate, tasks.size());
		}
		catch (DataIntegrityViolationException exception) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
	}

	@Override
	@Transactional(noRollbackFor = DataIntegrityViolationException.class)
	public MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		if (alreadyMaterialized(candidate)) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
		try {
			materializationRepository.saveAndFlush(materialization(
					candidate,
					PipelineMaterializationStatus.SKIPPED,
					null,
					null,
					Instant.now()));
			return MaterializationResult.skipped(candidate);
		}
		catch (DataIntegrityViolationException exception) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
	}

	@Override
	@Transactional(noRollbackFor = DataIntegrityViolationException.class)
	public MaterializationResult markFailed(
			EventPipelineMaterializationCandidate candidate,
			String failureKind,
			RuntimeException error) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		if (alreadyMaterialized(candidate)) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
		try {
			materializationRepository.saveAndFlush(materialization(
					candidate,
					PipelineMaterializationStatus.FAILED,
					failureKind,
					truncateError(error == null ? null : error.getMessage()),
					Instant.now()));
			return MaterializationResult.failed(candidate);
		}
		catch (DataIntegrityViolationException exception) {
			return MaterializationResult.alreadyMaterialized(candidate);
		}
	}

	private boolean alreadyMaterialized(EventPipelineMaterializationCandidate candidate) {
		PipelineDefinition definition = candidate.pipeline();
		return materializationRepository.findByEventIdAndPipelineIdAndPipelineVersion(
				candidate.event().id(),
				definition.pipelineId().value(),
				definition.pipelineVersion())
				.isPresent();
	}

	private JpaPipelineMaterializationEntity materialization(
			EventPipelineMaterializationCandidate candidate,
			PipelineMaterializationStatus status,
			String failureKind,
			String lastError,
			Instant now) {
		return new JpaPipelineMaterializationEntity(
				candidate.event().id(),
				candidate.pipeline().pipelineId().value(),
				candidate.pipeline().pipelineVersion(),
				status,
				failureKind,
				lastError,
				now);
	}

	private static JpaPipelineTaskEntity task(
			EventPipelineMaterializationCandidate candidate,
			java.util.UUID materializationId,
			TaskDescriptor task,
			Instant now) {
		return new JpaPipelineTaskEntity(
				materializationId,
				candidate.event().id(),
				candidate.pipeline().pipelineId().value(),
				candidate.pipeline().pipelineVersion(),
				task,
				now);
	}

	private static String truncateError(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 4000 ? error : error.substring(0, 4000);
	}
}
