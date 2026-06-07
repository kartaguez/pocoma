package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.task.ConfiguredTaskExecutionBinding;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTaskStatus;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskClaimCriteria;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskWorkSource;

@Component
public class JpaPipelineTaskWorkSourceAdapter implements PipelineTaskWorkSource {

	private final JpaPipelineTaskRepository repository;

	public JpaPipelineTaskWorkSourceAdapter(JpaPipelineTaskRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional
	public List<ClaimedWork<PipelineTask>> claim(ClaimWorkRequest<PipelineTaskClaimCriteria> request) {
		Objects.requireNonNull(request, "request must not be null");
		requirePositive(request.limit(), "limit");
		requirePositive(request.leaseDuration(), "leaseDuration");
		Objects.requireNonNull(request.criteria(), "criteria must not be null");
		requireText(request.workerId(), "workerId");
		Instant now = Instant.now();
		List<ClaimedWork<PipelineTask>> claimed = new ArrayList<>();
		for (ConfiguredTaskExecutionBinding binding : request.criteria().activeBindings()) {
			if (!binding.enabled()) {
				continue;
			}
			int remaining = request.limit() - claimed.size();
			if (remaining < 1) {
				break;
			}
			for (JpaPipelineTaskEntity entity : repository.findClaimable(
					now,
					remaining,
					request.criteria().partition().segmentIndex(),
					request.criteria().partition().segmentCount(),
					binding.definition().pipelineId().value(),
					binding.definition().pipelineVersion(),
					matchesEveryTaskType(binding),
					taskTypes(binding))) {
				UUID claimToken = UUID.randomUUID();
				entity.claim(claimToken, request.workerId(), now, now.plus(request.leaseDuration()));
				claimed.add(new ClaimedWork<>(entity.toTask()));
			}
		}
		return List.copyOf(claimed);
	}

	@Override
	@Transactional
	public boolean markAccepted(ClaimedWork<PipelineTask> work) {
		PipelineTask task = work.instruction();
		return repository.markAccepted(task.taskId(), task.claimToken(), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public void release(ClaimedWork<PipelineTask> work) {
		PipelineTask task = work.instruction();
		repository.release(task.taskId(), task.claimToken(), Instant.now());
	}

	@Override
	@Transactional
	public boolean markProcessing(ClaimedWork<PipelineTask> work) {
		PipelineTask task = work.instruction();
		return repository.markRunning(task.taskId(), task.claimToken(), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean heartbeat(ClaimedWork<PipelineTask> work, Duration leaseDuration) {
		requirePositive(leaseDuration, "leaseDuration");
		PipelineTask task = work.instruction();
		Instant now = Instant.now();
		return repository.heartbeat(task.taskId(), task.claimToken(), now.plus(leaseDuration), now) == 1;
	}

	@Override
	@Transactional
	public boolean markDone(ClaimedWork<PipelineTask> work) {
		PipelineTask task = work.instruction();
		return repository.markDone(task.taskId(), task.claimToken(), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markFailed(ClaimedWork<PipelineTask> work, RuntimeException error) {
		PipelineTask task = work.instruction();
		return repository.markFailed(
				task.taskId(),
				task.claimToken(),
				error == null ? null : error.getClass().getSimpleName(),
				truncateError(error == null ? null : error.getMessage()),
				Instant.now()) == 1;
	}

	@Override
	@Transactional(readOnly = true)
	public long countPendingOrInProgress(PipelineTaskClaimCriteria criteria) {
		return count(criteria, List.of(
				PipelineTaskStatus.PENDING,
				PipelineTaskStatus.CLAIMED,
				PipelineTaskStatus.ACCEPTED,
				PipelineTaskStatus.RUNNING));
	}

	@Override
	@Transactional(readOnly = true)
	public long countFailed(PipelineTaskClaimCriteria criteria) {
		return count(criteria, List.of(PipelineTaskStatus.FAILED));
	}

	private long count(PipelineTaskClaimCriteria criteria, List<PipelineTaskStatus> statuses) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		return criteria.activeBindings().stream()
				.filter(ConfiguredTaskExecutionBinding::enabled)
				.mapToLong(binding -> repository.countByBindingAndStatuses(
						binding.definition().pipelineId().value(),
						binding.definition().pipelineVersion(),
						criteria.partition().segmentIndex(),
						criteria.partition().segmentCount(),
						matchesEveryTaskType(binding),
						taskTypes(binding),
						statuses))
				.sum();
	}

	private static boolean matchesEveryTaskType(ConfiguredTaskExecutionBinding binding) {
		return binding.taskTypes().contains("*");
	}

	private static List<String> taskTypes(ConfiguredTaskExecutionBinding binding) {
		List<String> taskTypes = binding.taskTypes().stream()
				.filter(taskType -> !"*".equals(taskType))
				.toList();
		return taskTypes.isEmpty() ? List.of("__none__") : taskTypes;
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private static String truncateError(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 4000 ? error : error.substring(0, 4000);
	}
}
