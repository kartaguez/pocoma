package com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineMaterializationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_4_pipeline_materialization_status")
public class JpaPipelineMaterializationEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "pipeline_id", nullable = false, updatable = false)
	private String pipelineId;

	@Column(name = "pipeline_version", nullable = false, updatable = false)
	private int pipelineVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PipelineMaterializationStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "failure_kind")
	private String failureKind;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "materialized_at")
	private Instant materializedAt;

	@Column(name = "skipped_at")
	private Instant skippedAt;

	@Column(name = "failed_at")
	private Instant failedAt;

	protected JpaPipelineMaterializationEntity() {
	}

	public JpaPipelineMaterializationEntity(
			UUID eventId,
			String pipelineId,
			int pipelineVersion,
			PipelineMaterializationStatus status,
			String failureKind,
			String lastError,
			Instant now) {
		this.id = UUID.randomUUID();
		this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
		this.pipelineId = requireText(pipelineId, "pipelineId");
		this.pipelineVersion = pipelineVersion;
		this.status = Objects.requireNonNull(status, "status must not be null");
		this.attemptCount = status == PipelineMaterializationStatus.FAILED ? 1 : 0;
		this.failureKind = failureKind;
		this.lastError = lastError;
		this.createdAt = Objects.requireNonNull(now, "now must not be null");
		this.updatedAt = now;
		if (status == PipelineMaterializationStatus.MATERIALIZED) {
			this.materializedAt = now;
		}
		if (status == PipelineMaterializationStatus.SKIPPED) {
			this.skippedAt = now;
		}
		if (status == PipelineMaterializationStatus.FAILED) {
			this.failedAt = now;
		}
	}

	public UUID id() {
		return id;
	}

	public PipelineMaterializationStatus status() {
		return status;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
