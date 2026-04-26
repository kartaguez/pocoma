package com.kartaguez.pocoma.domain.aggregate;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class ExpenseHeader {

	private final ExpenseId id;
	private final PotId potId;
	private ShareholderId payerId;
	private Amount amount;
	private Label label;
	private boolean deleted;

	private ExpenseHeader(
			ExpenseId id,
			PotId potId,
			ShareholderId payerId,
			Amount amount,
			Label label,
			boolean deleted) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.payerId = Objects.requireNonNull(payerId, "payerId must not be null");
		this.amount = Objects.requireNonNull(amount, "amount must not be null");
		this.label = Objects.requireNonNull(label, "label must not be null");
		this.deleted = deleted;
	}

	public static ExpenseHeader reconstitute(
			ExpenseId id,
			PotId potId,
			ShareholderId payerId,
			Amount amount,
			Label label,
			boolean deleted) {
		return new ExpenseHeader(id, potId, payerId, amount, label, deleted);
	}

	public void markAsDeleted() {
		assertNotDeleted();

		deleted = true;
	}

	public void updateDetails(ShareholderId payerId, Amount amount, Label label) {
		assertNotDeleted();

		this.payerId = Objects.requireNonNull(payerId, "payerId must not be null");
		this.amount = Objects.requireNonNull(amount, "amount must not be null");
		this.label = Objects.requireNonNull(label, "label must not be null");
	}

	public ExpenseId id() {
		return id;
	}

	public PotId potId() {
		return potId;
	}

	public ShareholderId payerId() {
		return payerId;
	}

	public Amount amount() {
		return amount;
	}

	public Label label() {
		return label;
	}

	public boolean deleted() {
		return deleted;
	}

	private void assertNotDeleted() {
		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_DELETED",
					"Expense cannot be modified because it is deleted");
		}
	}
}
