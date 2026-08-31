package com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "consumption_results")
public class JpaConsumptionResultEntity {

	@Id
	@Column(name = "result_id", nullable = false, updatable = false)
	private UUID resultId;

	@Column(name = "slot_id", nullable = false, updatable = false)
	private UUID slotId;

	@Column(name = "space", nullable = false, updatable = false)
	private String space;

	@Column(name = "object_type", nullable = false, updatable = false)
	private String objectType;

	@Column(name = "object_id", nullable = false, updatable = false)
	private String objectId;

	@Column(name = "object_version", updatable = false)
	private Long objectVersion;

	@Column(name = "subject_type", updatable = false)
	private String subjectType;

	@Column(name = "subject_id", updatable = false)
	private String subjectId;

	@Column(name = "subject_version", updatable = false)
	private Long subjectVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected JpaConsumptionResultEntity() {
	}

	public JpaConsumptionResultEntity(
			UUID resultId,
			UUID slotId,
			String space,
			String objectType,
			String objectId,
			Long objectVersion,
			String subjectType,
			String subjectId,
			Long subjectVersion,
			Instant createdAt) {
		this.resultId = resultId;
		this.slotId = slotId;
		this.space = space;
		this.objectType = objectType;
		this.objectId = objectId;
		this.objectVersion = objectVersion;
		this.subjectType = subjectType;
		this.subjectId = subjectId;
		this.subjectVersion = subjectVersion;
		this.createdAt = createdAt;
	}

	public UUID resultId() { return resultId; }
	public UUID slotId() { return slotId; }
	public String space() { return space; }
	public String objectType() { return objectType; }
	public String objectId() { return objectId; }
	public Long objectVersion() { return objectVersion; }
	public String subjectType() { return subjectType; }
	public String subjectId() { return subjectId; }
	public Long subjectVersion() { return subjectVersion; }
	public Instant createdAt() { return createdAt; }
}
