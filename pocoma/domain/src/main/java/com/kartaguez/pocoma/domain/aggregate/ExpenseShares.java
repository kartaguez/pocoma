package com.kartaguez.pocoma.domain.aggregate;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class ExpenseShares {

	private final PotId potId;
	private Map<ShareholderId, ExpenseShare> shares;

	private ExpenseShares(PotId potId, Set<ExpenseShare> shares) {
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.shares = mergeShares(shares);
	}

	public static ExpenseShares reconstitute(PotId potId, Set<ExpenseShare> shares) {
		return new ExpenseShares(potId, shares);
	}

	public void updateExpenseShares(Set<ShareholderId> shareholderIds, Set<ExpenseShare> shares) {
		Set<ShareholderId> knownShareholderIds =
				Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
		Map<ShareholderId, ExpenseShare> mergedShares = mergeShares(shares);

		for (ShareholderId shareholderId : mergedShares.keySet()) {
			if (!knownShareholderIds.contains(shareholderId)) {
				throw new BusinessRuleViolationException(
						"SHAREHOLDER_NOT_PRESENT",
						"Expense share references a shareholder that does not belong to this pot");
			}
		}

		this.shares = mergedShares;
	}

	public PotId potId() {
		return potId;
	}

	public Map<ShareholderId, ExpenseShare> shares() {
		return Map.copyOf(shares);
	}

	private static Map<ShareholderId, ExpenseShare> mergeShares(Set<ExpenseShare> shares) {
		Map<ShareholderId, ExpenseShare> mergedShares = new HashMap<>();

		for (ExpenseShare share : Set.copyOf(Objects.requireNonNull(shares, "shares must not be null"))) {
			mergedShares.merge(share.shareholderId(), share, ExpenseShares::mergeShareWeights);
		}

		return Map.copyOf(mergedShares);
	}

	private static ExpenseShare mergeShareWeights(ExpenseShare left, ExpenseShare right) {
		Weight weight = Weight.of(left.weight().value().add(right.weight().value()));

		return new ExpenseShare(left.expenseId(), left.shareholderId(), weight);
	}
}
