package com.kartaguez.pocoma.infra.persistence.jpa.entity.projection;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pot_balance_projection_states")
public class JpaPotBalanceProjectionStateEntity {

	@Id
	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "projected_version", nullable = false)
	private long projectedVersion;

	protected JpaPotBalanceProjectionStateEntity() {
	}

	public JpaPotBalanceProjectionStateEntity(UUID potId, long projectedVersion) {
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.projectedVersion = projectedVersion;
	}

	public static JpaPotBalanceProjectionStateEntity from(PotBalanceProjectionState state) {
		Objects.requireNonNull(state, "state must not be null");
		return new JpaPotBalanceProjectionStateEntity(state.potId().value(), state.projectedVersion());
	}

	public PotBalanceProjectionState toDomain() {
		return new PotBalanceProjectionState(PotId.of(potId), projectedVersion);
	}

	public UUID potId() {
		return potId;
	}

	public long projectedVersion() {
		return projectedVersion;
	}
}
