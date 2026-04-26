package com.kartaguez.pocoma.infra.persistence.jpa.entity.core;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_headers")
public class JpaExpenseHeaderEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "expense_id", nullable = false, updatable = false)
	private UUID expenseId;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "started_at_version", nullable = false, updatable = false)
	private long startedAtVersion;

	@Column(name = "ended_at_version")
	private Long endedAtVersion;

	@Column(name = "payer_id", nullable = false)
	private UUID payerId;

	@Column(name = "amount_numerator", nullable = false)
	private long amountNumerator;

	@Column(name = "amount_denominator", nullable = false)
	private long amountDenominator;

	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "deleted", nullable = false)
	private boolean deleted;

	protected JpaExpenseHeaderEntity() {
	}

	public JpaExpenseHeaderEntity(
			UUID expenseId,
			UUID potId,
			long startedAtVersion,
			Long endedAtVersion,
			UUID payerId,
			long amountNumerator,
			long amountDenominator,
			String label,
			boolean deleted) {
		this.id = UUID.randomUUID();
		this.expenseId = Objects.requireNonNull(expenseId, "expenseId must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.startedAtVersion = startedAtVersion;
		this.endedAtVersion = endedAtVersion;
		this.payerId = Objects.requireNonNull(payerId, "payerId must not be null");
		this.amountNumerator = amountNumerator;
		this.amountDenominator = amountDenominator;
		this.label = Objects.requireNonNull(label, "label must not be null");
		this.deleted = deleted;
	}

	public static JpaExpenseHeaderEntity from(
			ExpenseHeader expenseHeader,
			long startedAtVersion,
			Long endedAtVersion) {
		Objects.requireNonNull(expenseHeader, "expenseHeader must not be null");
		Fraction amount = expenseHeader.amount().value();
		return new JpaExpenseHeaderEntity(
				expenseHeader.id().value(),
				expenseHeader.potId().value(),
				startedAtVersion,
				endedAtVersion,
				expenseHeader.payerId().value(),
				amount.numerator(),
				amount.denominator(),
				expenseHeader.label().value(),
				expenseHeader.deleted());
	}

	public ExpenseHeader toDomain() {
		return ExpenseHeader.reconstitute(
				ExpenseId.of(expenseId),
				PotId.of(potId),
				ShareholderId.of(payerId),
				Amount.of(Fraction.of(amountNumerator, amountDenominator)),
				Label.of(label),
				deleted);
	}

	public UUID id() {
		return id;
	}

	public UUID expenseId() {
		return expenseId;
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

	public boolean deleted() {
		return deleted;
	}
}
