package com.kartaguez.pocoma.infra.persistence.jpa.entity;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pot_headers")
public class JpaPotHeaderEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "started_at_version", nullable = false, updatable = false)
	private long startedAtVersion;

	@Column(name = "ended_at_version")
	private Long endedAtVersion;

	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "creator_id", nullable = false, updatable = false)
	private UUID creatorId;

	@Column(name = "deleted", nullable = false)
	private boolean deleted;

	protected JpaPotHeaderEntity() {
	}

	public JpaPotHeaderEntity(
			UUID potId,
			long startedAtVersion,
			Long endedAtVersion,
			String label,
			UUID creatorId,
			boolean deleted) {
		this.id = UUID.randomUUID();
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.startedAtVersion = startedAtVersion;
		this.endedAtVersion = endedAtVersion;
		this.label = Objects.requireNonNull(label, "label must not be null");
		this.creatorId = Objects.requireNonNull(creatorId, "creatorId must not be null");
		this.deleted = deleted;
	}

	public static JpaPotHeaderEntity from(PotHeader potHeader, long startedAtVersion, Long endedAtVersion) {
		Objects.requireNonNull(potHeader, "potHeader must not be null");
		return new JpaPotHeaderEntity(
				potHeader.id().value(),
				startedAtVersion,
				endedAtVersion,
				potHeader.label().value(),
				potHeader.creatorId().value(),
				potHeader.deleted());
	}

	public PotHeader toDomain() {
		return PotHeader.reconstitute(
				PotId.of(potId),
				Label.of(label),
				UserId.of(creatorId),
				deleted);
	}

	public UUID id() {
		return id;
	}

	public UUID potId() {
		return potId;
	}

	public long startedAtVersion() {
		return startedAtVersion;
	}

	public Long endedAtVersion() {
		return endedAtVersion;
	}

	public String label() {
		return label;
	}

	public UUID creatorId() {
		return creatorId;
	}

	public boolean deleted() {
		return deleted;
	}
}
