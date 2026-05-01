package com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projection_tasks")
public class JpaProjectionTaskEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "task_type", nullable = false)
	private ProjectionTaskType taskType;

	@Column(name = "pot_id", nullable = false)
	private UUID potId;

	@Column(name = "pot_partition_hash", nullable = false)
	private int potPartitionHash;

	@Column(name = "target_version", nullable = false)
	private long targetVersion;

	@Column(name = "source_event_type")
	private String sourceEventType;

	@Column(name = "source_event_min_id", nullable = false)
	private UUID sourceEventMinId;

	@Column(name = "source_event_max_id", nullable = false)
	private UUID sourceEventMaxId;

	@Column(name = "trace_id")
	private String traceId;

	@Column(name = "command_committed_at_nanos")
	private Long commandCommittedAtNanos;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ProjectionTaskStatus status;

	@Column(name = "claim_token")
	private UUID claimToken;

	@Column(name = "claimed_by")
	private String claimedBy;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "done_at")
	private Instant doneAt;

	@Column(name = "failed_at")
	private Instant failedAt;

	@Column(name = "last_error")
	private String lastError;

	protected JpaProjectionTaskEntity() {
	}

	public JpaProjectionTaskEntity(
			ProjectionTaskType taskType,
			UUID potId,
			long targetVersion,
			String sourceEventType,
			UUID sourceEventId,
			String traceId,
			Long commandCommittedAtNanos,
			Instant now) {
		this.id = UUID.randomUUID();
		this.taskType = Objects.requireNonNull(taskType, "taskType must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.potPartitionHash = PotPartitioner.partitionHash(potId);
		this.targetVersion = targetVersion;
		this.sourceEventType = sourceEventType;
		this.sourceEventMinId = Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
		this.sourceEventMaxId = sourceEventId;
		this.traceId = traceId;
		this.commandCommittedAtNanos = commandCommittedAtNanos;
		this.status = ProjectionTaskStatus.PENDING;
		this.attemptCount = 0;
		this.createdAt = Objects.requireNonNull(now, "now must not be null");
		this.updatedAt = now;
	}

	public ProjectionTaskDescriptor toDescriptor() {
		return new ProjectionTaskDescriptor(
				id,
				taskType,
				PotId.of(potId),
				targetVersion,
				sourceEventType,
				sourceEventMinId,
				sourceEventMaxId,
				traceId,
				commandCommittedAtNanos,
				createdAt);
	}

	public void coalesce(long targetVersion, UUID sourceEventId, String sourceEventType, String traceId,
			Long commandCommittedAtNanos, Instant now) {
		if (targetVersion > this.targetVersion) {
			this.targetVersion = targetVersion;
			this.sourceEventType = sourceEventType;
			this.sourceEventMaxId = Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
			this.traceId = traceId;
			this.commandCommittedAtNanos = commandCommittedAtNanos;
		}
		this.updatedAt = Objects.requireNonNull(now, "now must not be null");
		this.lastError = null;
		if (this.status == ProjectionTaskStatus.FAILED) {
			this.status = ProjectionTaskStatus.PENDING;
			this.claimToken = null;
			this.claimedBy = null;
			this.leaseUntil = null;
			this.failedAt = null;
		}
	}

	public void claim(UUID claimToken, String workerId, Instant now, Instant leaseUntil) {
		this.status = ProjectionTaskStatus.CLAIMED;
		this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
		this.claimedBy = requireText(workerId, "workerId");
		this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
		this.claimedAt = Objects.requireNonNull(now, "now must not be null");
		this.updatedAt = now;
		this.attemptCount++;
		this.lastError = null;
	}

	public UUID id() {
		return id;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
