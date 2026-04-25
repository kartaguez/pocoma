package com.kartaguez.pocoma.domain.projection;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class PotBalancesCalculator {

	public PotBalances calculate(
			PotBalances previousBalances,
			long targetVersion,
			Collection<ProjectedExpense> activeAtPreviousOnly,
			Collection<ProjectedExpense> activeAtTargetOnly) {
		Objects.requireNonNull(previousBalances, "previousBalances must not be null");
		Objects.requireNonNull(activeAtPreviousOnly, "activeAtPreviousOnly must not be null");
		Objects.requireNonNull(activeAtTargetOnly, "activeAtTargetOnly must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (targetVersion <= previousBalances.version()) {
			throw new IllegalArgumentException("targetVersion must be greater than previousBalances version");
		}

		Map<ShareholderId, Balance> removedBalances =
				calculateExpensesBalances(previousBalances.potId(), activeAtPreviousOnly);
		Map<ShareholderId, Balance> addedBalances =
				calculateExpensesBalances(previousBalances.potId(), activeAtTargetOnly);
		Map<ShareholderId, Balance> result = BalanceMapOperations.add(
				BalanceMapOperations.subtract(previousBalances.balances(), removedBalances),
				addedBalances);

		return new PotBalances(previousBalances.potId(), targetVersion, result);
	}

	public Map<ShareholderId, Balance> calculateExpensesBalances(
			PotId potId,
			Collection<ProjectedExpense> expenses) {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(expenses, "expenses must not be null");

		Map<ShareholderId, Balance> result = Map.of();
		for (ProjectedExpense expense : expenses) {
			result = BalanceMapOperations.add(result, calculateExpenseBalances(potId, expense));
		}

		return result;
	}

	public Map<ShareholderId, Balance> calculateExpenseBalances(PotId potId, ProjectedExpense expense) {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(expense, "expense must not be null");
		ExpenseHeader header = expense.header();
		ExpenseShares shares = expense.shares();
		assertProjectable(potId, header, shares);

		Fraction totalWeight = shares.shares().values().stream()
				.map(share -> share.weight().value())
				.reduce(Fraction.ZERO, Fraction::add);
		if (totalWeight.compareTo(Fraction.ZERO) == 0) {
			throw new IllegalArgumentException("expense shares total weight must be greater than zero");
		}

		Map<ShareholderId, Balance> result = new HashMap<>();
		put(result, header.payerId(), header.amount().value());
		shares.shares().values().forEach(share -> {
			Fraction shareAmount = header.amount().value()
					.multiply(share.weight().value())
					.divide(totalWeight);
			Fraction debit = Fraction.ZERO.subtract(shareAmount);
			Fraction current = result.getOrDefault(share.shareholderId(), new Balance(share.shareholderId(), Fraction.ZERO))
					.value();
			put(result, share.shareholderId(), current.add(debit));
		});

		return Map.copyOf(result);
	}

	private static void assertProjectable(PotId potId, ExpenseHeader header, ExpenseShares shares) {
		if (!header.potId().equals(potId)) {
			throw new IllegalArgumentException("expense header must reference the projected pot");
		}
		if (!shares.potId().equals(potId)) {
			throw new IllegalArgumentException("expense shares must reference the projected pot");
		}
		if (header.deleted()) {
			throw new IllegalArgumentException("deleted expense cannot be projected");
		}
		for (ExpenseShare share : shares.shares().values()) {
			if (!share.expenseId().equals(header.id())) {
				throw new IllegalArgumentException("expense share must reference the projected expense");
			}
		}
	}

	private static void put(Map<ShareholderId, Balance> balances, ShareholderId shareholderId, Fraction value) {
		balances.put(shareholderId, new Balance(shareholderId, value));
	}
}
