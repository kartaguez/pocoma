package com.kartaguez.pocoma.infra.persistence.jpa.entity;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_shares")
public class JpaExpenseShareEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "expense_id", nullable = false, updatable = false)
	private UUID expenseId;

	@Column(name = "shareholder_id", nullable = false, updatable = false)
	private UUID shareholderId;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "started_at_version", nullable = false, updatable = false)
	private long startedAtVersion;

	@Column(name = "ended_at_version")
	private Long endedAtVersion;

	@Column(name = "weight_numerator", nullable = false)
	private long weightNumerator;

	@Column(name = "weight_denominator", nullable = false)
	private long weightDenominator;

	protected JpaExpenseShareEntity() {
	}

	public JpaExpenseShareEntity(
			UUID expenseId,
			UUID shareholderId,
			UUID potId,
			long startedAtVersion,
			Long endedAtVersion,
			long weightNumerator,
			long weightDenominator) {
		this.id = UUID.randomUUID();
		this.expenseId = Objects.requireNonNull(expenseId, "expenseId must not be null");
		this.shareholderId = Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.startedAtVersion = startedAtVersion;
		this.endedAtVersion = endedAtVersion;
		this.weightNumerator = weightNumerator;
		this.weightDenominator = weightDenominator;
	}

	public static JpaExpenseShareEntity from(
			PotId potId,
			ExpenseShare expenseShare,
			long startedAtVersion,
			Long endedAtVersion) {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(expenseShare, "expenseShare must not be null");
		Fraction weight = expenseShare.weight().value();
		return new JpaExpenseShareEntity(
				expenseShare.expenseId().value(),
				expenseShare.shareholderId().value(),
				potId.value(),
				startedAtVersion,
				endedAtVersion,
				weight.numerator(),
				weight.denominator());
	}

	public ExpenseShare toDomain() {
		return new ExpenseShare(
				ExpenseId.of(expenseId),
				ShareholderId.of(shareholderId),
				Weight.of(Fraction.of(weightNumerator, weightDenominator)));
	}

	public UUID id() {
		return id;
	}

	public UUID expenseId() {
		return expenseId;
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
