package com.kartaguez.pocoma.infra.persistence.jpa.entity;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pot_global_versions")
public class JpaPotGlobalVersionEntity {

	@Id
	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "version", nullable = false)
	private long version;

	protected JpaPotGlobalVersionEntity() {
	}

	public JpaPotGlobalVersionEntity(UUID potId, long version) {
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.version = version;
	}

	public static JpaPotGlobalVersionEntity from(PotGlobalVersion potGlobalVersion) {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		return new JpaPotGlobalVersionEntity(potGlobalVersion.potId().value(), potGlobalVersion.version());
	}

	public PotGlobalVersion toDomain() {
		return new PotGlobalVersion(PotId.of(potId), version);
	}

	public UUID potId() {
		return potId;
	}

	public long version() {
		return version;
	}
}
