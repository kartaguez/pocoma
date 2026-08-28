package com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tasks_4_pipeline")
public class JpaPipelineTaskEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "materialization_id", nullable = false, updatable = false)
	private UUID materializationId;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "pipeline_id", nullable = false, updatable = false)
	private String pipelineId;

	@Column(name = "pipeline_version", nullable = false, updatable = false)
	private int pipelineVersion;

	@Column(name = "task_type", nullable = false, updatable = false)
	private String taskType;

	@Column(name = "task_key", nullable = false, updatable = false)
	private String taskKey;

	@Column(name = "task_payload", nullable = false, updatable = false)
	private String taskPayload;

	@Column(name = "partition_key", updatable = false)
	private String partitionKey;

	@Column(name = "partition_hash", nullable = false, updatable = false)
	private int partitionHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private JpaPipelineTaskStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "claim_token")
	private UUID claimToken;

	@Column(name = "claimed_by")
	private String claimedBy;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "failure_kind")
	private String failureKind;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
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

	protected JpaPipelineTaskEntity() {
	}

	public JpaPipelineTaskEntity(
			UUID materializationId,
			UUID eventId,
			String pipelineId,
			int pipelineVersion,
			TaskDescriptor task,
			Instant now) {
		this.id = UUID.randomUUID();
		this.materializationId = Objects.requireNonNull(materializationId, "materializationId must not be null");
		this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
		this.pipelineId = requireText(pipelineId, "pipelineId");
		this.pipelineVersion = pipelineVersion;
		this.taskType = requireText(task.taskType(), "taskType");
		this.taskKey = requireText(task.taskKey(), "taskKey");
		this.taskPayload = requireText(task.taskPayload(), "taskPayload");
		this.partitionKey = task.partitionKey();
		this.partitionHash = partitionHash(task.partitionKey());
		this.status = JpaPipelineTaskStatus.PENDING;
		this.attemptCount = 0;
		this.createdAt = Objects.requireNonNull(now, "now must not be null");
		this.updatedAt = now;
	}

	public void claim(UUID claimToken, String workerId, Instant now, Instant leaseUntil) {
		this.status = JpaPipelineTaskStatus.CLAIMED;
		this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
		this.claimedBy = requireText(workerId, "workerId");
		this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
		this.claimedAt = Objects.requireNonNull(now, "now must not be null");
		this.updatedAt = now;
		this.attemptCount++;
		this.failureKind = null;
		this.lastError = null;
	}

	public LegacyPipelineTask toTask() {
		return new LegacyPipelineTask(
				id,
				Objects.requireNonNull(claimToken, "claimToken must not be null"),
				materializationId,
				eventId,
				new PipelineDefinition(PipelineId.of(pipelineId), pipelineVersion),
				taskType,
				taskKey,
				taskPayload,
				partitionKey,
				potIdFromPartitionKey(),
				null,
				null,
				createdAt,
				System.nanoTime());
	}

	public UUID id() {
		return id;
	}

	private PotId potIdFromPartitionKey() {
		if (partitionKey == null) {
			throw new IllegalStateException("partitionKey must contain a pot id for executable pipeline tasks");
		}
		return PotId.of(UUID.fromString(partitionKey));
	}

	private static int partitionHash(String partitionKey) {
		String stableKey = partitionKey == null ? "" : partitionKey;
		return stableKey.hashCode() & Integer.MAX_VALUE;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
