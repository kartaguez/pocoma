package com.kartaguez.pocoma.infra.persistence.jpa.entity.projection;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "pot_balances",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_pot_balances_pot_id_version_shareholder_id",
				columnNames = {"pot_id", "version", "shareholder_id"}))
public class JpaPotBalanceEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "version", nullable = false, updatable = false)
	private long version;

	@Column(name = "shareholder_id", nullable = false, updatable = false)
	private UUID shareholderId;

	@Column(name = "value_numerator", nullable = false)
	private long valueNumerator;

	@Column(name = "value_denominator", nullable = false)
	private long valueDenominator;

	protected JpaPotBalanceEntity() {
	}

	public JpaPotBalanceEntity(
			UUID potId,
			long version,
			UUID shareholderId,
			long valueNumerator,
			long valueDenominator) {
		this.id = UUID.randomUUID();
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.version = version;
		this.shareholderId = Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		this.valueNumerator = valueNumerator;
		this.valueDenominator = valueDenominator;
	}

	public static JpaPotBalanceEntity from(UUID potId, long version, Balance balance) {
		Objects.requireNonNull(balance, "balance must not be null");
		Fraction value = balance.value();
		return new JpaPotBalanceEntity(
				potId,
				version,
				balance.shareholderId().value(),
				value.numerator(),
				value.denominator());
	}

	public Balance toDomain() {
		return new Balance(
				ShareholderId.of(shareholderId),
				Fraction.of(valueNumerator, valueDenominator));
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

	public UUID shareholderId() {
		return shareholderId;
	}
}
