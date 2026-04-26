package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class PotBalancesCalculatorTest {

	private final PotBalancesCalculator calculator = new PotBalancesCalculator();

	@Test
	void calculatesBalancesForExpense() {
		Fixture fixture = new Fixture();
		ProjectedExpense expense = fixture.expense(
				fixture.payerId,
				Fraction.of(120, 1),
				Set.of(
						fixture.share(fixture.payerId, Fraction.ONE),
						fixture.share(fixture.aliceId, Fraction.of(2, 1)),
						fixture.share(fixture.bobId, Fraction.of(3, 1))));

		Map<ShareholderId, Balance> balances = calculator.calculateExpenseBalances(fixture.potId, expense);

		assertEquals(Fraction.of(100, 1), balances.get(fixture.payerId).value());
		assertEquals(Fraction.of(-40, 1), balances.get(fixture.aliceId).value());
		assertEquals(Fraction.of(-60, 1), balances.get(fixture.bobId).value());
		assertEquals(Fraction.ZERO, sum(balances));
	}

	@Test
	void rejectsDeletedExpense() {
		Fixture fixture = new Fixture();
		ProjectedExpense expense = new ProjectedExpense(
				fixture.header(fixture.expenseId, fixture.payerId, Fraction.of(10, 1), true),
				fixture.shares(Set.of(fixture.share(fixture.aliceId, Fraction.ONE))));

		assertThrows(IllegalArgumentException.class, () -> calculator.calculateExpenseBalances(fixture.potId, expense));
	}

	@Test
	void rejectsExpenseWithDifferentSharesPot() {
		Fixture fixture = new Fixture();
		ExpenseShares shares = ExpenseShares.reconstitute(
				PotId.of(UUID.randomUUID()),
				Set.of(fixture.share(fixture.aliceId, Fraction.ONE)));
		ProjectedExpense expense = new ProjectedExpense(
				fixture.header(fixture.expenseId, fixture.payerId, Fraction.of(10, 1), false),
				shares);

		assertThrows(IllegalArgumentException.class, () -> calculator.calculateExpenseBalances(fixture.potId, expense));
	}

	@Test
	void rejectsExpenseWithShareReferencingDifferentExpense() {
		Fixture fixture = new Fixture();
		ExpenseShare share = new ExpenseShare(
				ExpenseId.of(UUID.randomUUID()),
				fixture.aliceId,
				Weight.of(Fraction.ONE));
		ProjectedExpense expense = fixture.expense(fixture.payerId, Fraction.of(10, 1), Set.of(share));

		assertThrows(IllegalArgumentException.class, () -> calculator.calculateExpenseBalances(fixture.potId, expense));
	}

	@Test
	void rejectsExpenseWithZeroTotalWeight() {
		Fixture fixture = new Fixture();
		ProjectedExpense expense = fixture.expense(
				fixture.payerId,
				Fraction.of(10, 1),
				Set.of(fixture.share(fixture.aliceId, Fraction.ZERO)));

		assertThrows(IllegalArgumentException.class, () -> calculator.calculateExpenseBalances(fixture.potId, expense));
	}

	@Test
	void calculatesDifferentialPotBalances() {
		Fixture fixture = new Fixture();
		PotBalances previousBalances = new PotBalances(
				fixture.potId,
				4,
				Map.of(
						fixture.payerId, new Balance(fixture.payerId, Fraction.of(10, 1)),
						fixture.aliceId, new Balance(fixture.aliceId, Fraction.of(-10, 1))));
		ProjectedExpense removedExpense = fixture.expense(
				fixture.payerId,
				Fraction.of(20, 1),
				Set.of(fixture.share(fixture.aliceId, Fraction.ONE)));
		ProjectedExpense addedExpense = fixture.expense(
				fixture.bobId,
				Fraction.of(30, 1),
				Set.of(
						fixture.share(fixture.aliceId, Fraction.ONE),
						fixture.share(fixture.bobId, Fraction.ONE)));

		PotBalances result = calculator.calculate(
				previousBalances,
				7,
				Set.of(removedExpense),
				Set.of(addedExpense));

		assertEquals(7, result.version());
		assertEquals(Fraction.of(-10, 1), result.balances().get(fixture.payerId).value());
		assertEquals(Fraction.of(-5, 1), result.balances().get(fixture.aliceId).value());
		assertEquals(Fraction.of(15, 1), result.balances().get(fixture.bobId).value());
	}

	@Test
	void rejectsTargetVersionNotGreaterThanPreviousVersion() {
		Fixture fixture = new Fixture();
		PotBalances previousBalances = new PotBalances(fixture.potId, 4, Map.of());

		assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
				previousBalances,
				4,
				Set.of(),
				Set.of()));
	}

	private static Fraction sum(Map<ShareholderId, Balance> balances) {
		return balances.values().stream()
				.map(Balance::value)
				.reduce(Fraction.ZERO, Fraction::add);
	}

	private static final class Fixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId bobId = ShareholderId.of(UUID.randomUUID());

		private ProjectedExpense expense(
				ShareholderId payerId,
				Fraction amount,
				Set<ExpenseShare> shares) {
			return new ProjectedExpense(
					header(expenseId, payerId, amount, false),
					shares(shares));
		}

		private ExpenseHeader header(
				ExpenseId expenseId,
				ShareholderId payerId,
				Fraction amount,
				boolean deleted) {
			return ExpenseHeader.reconstitute(
					expenseId,
					potId,
					payerId,
					Amount.of(amount),
					Label.of("Expense"),
					deleted);
		}

		private ExpenseShares shares(Set<ExpenseShare> shares) {
			return ExpenseShares.reconstitute(potId, shares);
		}

		private ExpenseShare share(ShareholderId shareholderId, Fraction weight) {
			return new ExpenseShare(
					expenseId,
					shareholderId,
					Weight.of(weight));
		}
	}
}
