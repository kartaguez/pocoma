package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

/** Typed existing or prospective business object targeted by a Pot action. */
public sealed interface AuthorizationTarget {

	AuthorizationTargetType targetType();

	boolean existing();

	static ExistingPot existing(PotId potId) {
		return new ExistingPot(potId);
	}

	static ExistingExpense existing(ExpenseId expenseId) {
		return new ExistingExpense(expenseId);
	}

	static ExistingShareholder existing(ShareholderId shareholderId) {
		return new ExistingShareholder(shareholderId);
	}

	static ProspectiveExpense prospectiveExpense() {
		return ProspectiveExpense.INSTANCE;
	}

	static ProspectiveShareholder prospectiveShareholder() {
		return ProspectiveShareholder.INSTANCE;
	}

	record ExistingPot(PotId targetId) implements AuthorizationTarget {
		public ExistingPot {
			requireNonNull(targetId, "targetId must not be null");
		}

		@Override public AuthorizationTargetType targetType() { return AuthorizationTargetType.POT; }
		@Override public boolean existing() { return true; }
	}

	record ExistingExpense(ExpenseId targetId) implements AuthorizationTarget {
		public ExistingExpense {
			requireNonNull(targetId, "targetId must not be null");
		}

		@Override public AuthorizationTargetType targetType() { return AuthorizationTargetType.EXPENSE; }
		@Override public boolean existing() { return true; }
	}

	record ExistingShareholder(ShareholderId targetId) implements AuthorizationTarget {
		public ExistingShareholder {
			requireNonNull(targetId, "targetId must not be null");
		}

		@Override public AuthorizationTargetType targetType() { return AuthorizationTargetType.SHAREHOLDER; }
		@Override public boolean existing() { return true; }
	}

	enum ProspectiveExpense implements AuthorizationTarget {
		INSTANCE;

		@Override public AuthorizationTargetType targetType() { return AuthorizationTargetType.EXPENSE; }
		@Override public boolean existing() { return false; }
	}

	enum ProspectiveShareholder implements AuthorizationTarget {
		INSTANCE;

		@Override public AuthorizationTargetType targetType() { return AuthorizationTargetType.SHAREHOLDER; }
		@Override public boolean existing() { return false; }
	}
}
