package com.kartaguez.pocoma.infra.persistence.jpa.entity.projection;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "pot_balance_versions",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_pot_balance_versions_pot_id_version",
				columnNames = {"pot_id", "version"}))
public class JpaPotBalanceVersionEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "version", nullable = false, updatable = false)
	private long version;

	protected JpaPotBalanceVersionEntity() {
	}

	public JpaPotBalanceVersionEntity(UUID potId, long version) {
		this.id = UUID.randomUUID();
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.version = version;
	}

	public UUID id() {
		return id;
	}

	public UUID potId() {
		return potId;
	}

	public long version() {
		return version;
	}
}
