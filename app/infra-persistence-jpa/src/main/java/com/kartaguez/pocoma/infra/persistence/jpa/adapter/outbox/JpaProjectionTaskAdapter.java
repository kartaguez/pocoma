package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaProjectionTaskEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaProjectionTaskRepository;

@Component
public class JpaProjectionTaskAdapter implements ProjectionTaskPort {

	private static final List<ProjectionTaskStatus> ACTIVE_STATUSES = List.of(
			ProjectionTaskStatus.PENDING,
			ProjectionTaskStatus.CLAIMED,
			ProjectionTaskStatus.ACCEPTED,
			ProjectionTaskStatus.RUNNING);

	private final JpaProjectionTaskRepository repository;

	public JpaProjectionTaskAdapter(JpaProjectionTaskRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional
	public ProjectionTaskDescriptor upsertComputeBalancesTask(BusinessEventEnvelope sourceEvent) {
		Objects.requireNonNull(sourceEvent, "sourceEvent must not be null");
		Instant now = Instant.now();
		JpaProjectionTaskEntity task = repository.findActive(
				sourceEvent.potId().value(),
				ProjectionTaskType.COMPUTE_BALANCES_FOR_VERSION,
				ACTIVE_STATUSES)
				.map(existingTask -> {
					existingTask.coalesce(
								sourceEvent.version(),
								sourceEvent.id(),
								sourceEvent.eventType(),
								sourceEvent.traceId(),
								sourceEvent.commandCommittedAtNanos(),
								now);
					return existingTask;
				})
				.orElseGet(() -> repository.save(new JpaProjectionTaskEntity(
								ProjectionTaskType.COMPUTE_BALANCES_FOR_VERSION,
								sourceEvent.potId().value(),
								sourceEvent.version(),
								sourceEvent.eventType(),
								sourceEvent.id(),
								sourceEvent.traceId(),
								sourceEvent.commandCommittedAtNanos(),
								now)));
		return task.toDescriptor();
	}

	@Override
	@Transactional
	public List<ProjectionTaskClaim> claimPending(int limit, Duration leaseDuration, String workerId) {
		return claimPending(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	@Override
	@Transactional
	public List<ProjectionTaskClaim> claimPending(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		requirePositive(limit, "limit");
		Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		if (leaseDuration.isNegative() || leaseDuration.isZero()) {
			throw new IllegalArgumentException("leaseDuration must be positive");
		}
		requireText(workerId, "workerId");
		Instant now = Instant.now();
		return repository.findClaimable(now, limit, partition.segmentIndex(), partition.segmentCount()).stream()
				.map(entity -> claim(entity, workerId, now, now.plus(leaseDuration)))
				.toList();
	}

	@Override
	@Transactional
	public boolean markAccepted(UUID taskId, UUID claimToken) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markAccepted(taskId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markRunning(UUID taskId, UUID claimToken) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markRunning(taskId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markDone(UUID taskId, UUID claimToken) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markDone(taskId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markFailed(UUID taskId, UUID claimToken, String error) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markFailed(taskId, claimToken, truncateError(error), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean release(UUID taskId, UUID claimToken) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.release(taskId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean heartbeat(UUID taskId, UUID claimToken, Duration leaseDuration) {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
		if (leaseDuration.isNegative() || leaseDuration.isZero()) {
			throw new IllegalArgumentException("leaseDuration must be positive");
		}
		Instant now = Instant.now();
		return repository.heartbeat(taskId, claimToken, now.plus(leaseDuration), now) == 1;
	}

	@Override
	@Transactional(readOnly = true)
	public long countPendingOrInProgress() {
		return repository.countPendingOrInProgress();
	}

	@Override
	@Transactional(readOnly = true)
	public long countPendingOrInProgress(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return repository.countPendingOrInProgress(potId.value());
	}

	private ProjectionTaskClaim claim(
			JpaProjectionTaskEntity entity,
			String workerId,
			Instant now,
			Instant leaseUntil) {
		UUID claimToken = UUID.randomUUID();
		entity.claim(claimToken, workerId, now, leaseUntil);
		return new ProjectionTaskClaim(entity.toDescriptor(), claimToken);
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
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
