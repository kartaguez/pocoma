package com.kartaguez.pocoma.infra.persistence.jpa.entity.core;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shareholders")
public class JpaShareholderEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shareholder_id", nullable = false, updatable = false)
	private UUID shareholderId;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "started_at_version", nullable = false, updatable = false)
	private long startedAtVersion;

	@Column(name = "ended_at_version")
	private Long endedAtVersion;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "weight_numerator", nullable = false)
	private long weightNumerator;

	@Column(name = "weight_denominator", nullable = false)
	private long weightDenominator;

	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "deleted", nullable = false)
	private boolean deleted;

	protected JpaShareholderEntity() {
	}

	public JpaShareholderEntity(
			UUID shareholderId,
			UUID potId,
			long startedAtVersion,
			Long endedAtVersion,
			String name,
			long weightNumerator,
			long weightDenominator,
			UUID userId,
			boolean deleted) {
		this.id = UUID.randomUUID();
		this.shareholderId = Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.startedAtVersion = startedAtVersion;
		this.endedAtVersion = endedAtVersion;
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.weightNumerator = weightNumerator;
		this.weightDenominator = weightDenominator;
		this.userId = userId;
		this.deleted = deleted;
	}

	public static JpaShareholderEntity from(Shareholder shareholder, long startedAtVersion, Long endedAtVersion) {
		Objects.requireNonNull(shareholder, "shareholder must not be null");
		Fraction weight = shareholder.weight().value();
		return new JpaShareholderEntity(
				shareholder.id().value(),
				shareholder.potId().value(),
				startedAtVersion,
				endedAtVersion,
				shareholder.name().value(),
				weight.numerator(),
				weight.denominator(),
				shareholder.userId() == null ? null : shareholder.userId().value(),
				shareholder.deleted());
	}

	public Shareholder toDomain() {
		return Shareholder.reconstitute(
				ShareholderId.of(shareholderId),
				PotId.of(potId),
				Name.of(name),
				Weight.of(Fraction.of(weightNumerator, weightDenominator)),
				userId == null ? null : UserId.of(userId),
				deleted);
	}

	public UUID id() {
		return id;
	}

	public UUID shareholderId() {
		return shareholderId;
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
}
