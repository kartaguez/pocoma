package com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Structural durable Task mapping; legacy lifecycle columns are intentionally not mapped. */
@Entity
@Table(name = "tasks_4_pipeline")
public class JpaPipelineTaskEntity {
	@Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
	@Column(name = "materialization_id", nullable = false, updatable = false) private UUID materializationId;
	@Column(name = "event_id", nullable = false, updatable = false) private UUID eventId;
	@Column(name = "pipeline_id", nullable = false, updatable = false) private String pipelineId;
	@Column(name = "pipeline_version", nullable = false, updatable = false) private int pipelineVersion;
	@Column(name = "task_type", nullable = false, updatable = false) private String taskType;
	@Column(name = "task_key", nullable = false, updatable = false) private String taskKey;
	@Column(name = "task_payload", nullable = false, updatable = false) private String taskPayload;
	@Column(name = "partition_key", updatable = false) private String partitionKey;
	@Column(name = "partition_hash", nullable = false, updatable = false) private int partitionHash;
	@Column(name = "target_version", nullable = false, updatable = false) private long targetVersion;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected JpaPipelineTaskEntity() {}

	public JpaPipelineTaskEntity(UUID materializationId, UUID eventId, String pipelineId,
			int pipelineVersion, TaskDescriptor task, Instant now) {
		this.id = UUID.randomUUID();
		this.materializationId = Objects.requireNonNull(materializationId);
		this.eventId = Objects.requireNonNull(eventId);
		this.pipelineId = requireText(pipelineId, "pipelineId");
		this.pipelineVersion = pipelineVersion;
		this.taskType = task.taskType();
		this.taskKey = task.taskKey();
		this.taskPayload = task.taskPayload();
		this.partitionKey = task.partitionKey();
		this.partitionHash = (task.partitionKey() == null ? "" : task.partitionKey()).hashCode() & Integer.MAX_VALUE;
		this.targetVersion = task.targetVersion();
		this.createdAt = Objects.requireNonNull(now);
		this.updatedAt = now;
	}

	public UUID id() { return id; }
	public String taskType() { return taskType; }
	public long targetVersion() { return targetVersion; }
	public Instant createdAt() { return createdAt; }

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
