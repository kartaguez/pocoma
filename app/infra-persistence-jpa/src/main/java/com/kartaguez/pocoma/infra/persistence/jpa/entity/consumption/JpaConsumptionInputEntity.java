package com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "consumption_inputs")
public class JpaConsumptionInputEntity {

	@Id
	@Column(name = "input_id", nullable = false, updatable = false)
	private UUID inputId;

	@Column(name = "slot_id", nullable = false, updatable = false)
	private UUID slotId;

	@Column(name = "subject_type", nullable = false, updatable = false)
	private String subjectType;

	@Column(name = "subject_id", nullable = false, updatable = false)
	private String subjectId;

	@Column(name = "subject_version", nullable = false, updatable = false)
	private long subjectVersion;

	protected JpaConsumptionInputEntity() {
	}

	public JpaConsumptionInputEntity(
			UUID inputId, UUID slotId, String subjectType, String subjectId, long subjectVersion) {
		this.inputId = inputId;
		this.slotId = slotId;
		this.subjectType = subjectType;
		this.subjectId = subjectId;
		this.subjectVersion = subjectVersion;
	}

	public UUID inputId() { return inputId; }
	public UUID slotId() { return slotId; }
	public String subjectType() { return subjectType; }
	public String subjectId() { return subjectId; }
	public long subjectVersion() { return subjectVersion; }
}
