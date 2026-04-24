package com.kartaguez.pocoma.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ExpenseSharesTest {

	@Test
	void reconstitutesExpenseShares() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShare share = fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 2)));

		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of(share));

		assertEquals(fixture.potId, expenseShares.potId());
		assertEquals(Map.of(fixture.aliceId, share), expenseShares.shares());
	}

	@Test
	void updatesExpenseShares() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of());
		ExpenseShare aliceShare = fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 2)));
		ExpenseShare bobShare = fixture.expenseShare(fixture.bobId, Weight.of(new Fraction(1, 2)));

		expenseShares.updateExpenseShares(Set.of(fixture.aliceId, fixture.bobId), Set.of(aliceShare, bobShare));

		assertEquals(Map.of(fixture.aliceId, aliceShare, fixture.bobId, bobShare), expenseShares.shares());
	}

	@Test
	void replacesExpenseSharesWhenUpdating() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShare aliceShare = fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 2)));
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of(aliceShare));
		ExpenseShare bobShare = fixture.expenseShare(fixture.bobId, Weight.of(new Fraction(1, 1)));

		expenseShares.updateExpenseShares(Set.of(fixture.aliceId, fixture.bobId), Set.of(bobShare));

		assertEquals(Map.of(fixture.bobId, bobShare), expenseShares.shares());
	}

	@Test
	void mergesExpenseSharesWithSameShareholderId() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of());
		Set<ExpenseShare> shares = new HashSet<>();
		shares.add(fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 4))));
		shares.add(fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 2))));

		expenseShares.updateExpenseShares(Set.of(fixture.aliceId), shares);

		assertEquals(1, expenseShares.shares().size());
		assertEquals(
				Weight.of(new Fraction(3, 4)),
				expenseShares.shares().get(fixture.aliceId).weight());
	}

	@Test
	void rejectsExpenseShareWithUnknownShareholderId() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of());
		ExpenseShare aliceShare = fixture.expenseShare(fixture.aliceId, Weight.of(new Fraction(1, 1)));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> expenseShares.updateExpenseShares(Set.of(fixture.bobId), Set.of(aliceShare)));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> ExpenseShares.reconstitute(null, Set.of()));
	}

	@Test
	void rejectsNullShares() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();

		assertThrows(NullPointerException.class, () -> ExpenseShares.reconstitute(fixture.potId, null));
	}

	@Test
	void rejectsNullShare() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		Set<ExpenseShare> shares = new HashSet<>();
		shares.add(null);

		assertThrows(NullPointerException.class, () -> ExpenseShares.reconstitute(fixture.potId, shares));
	}

	@Test
	void rejectsNullShareholderIdsWhenUpdating() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of());

		assertThrows(NullPointerException.class, () -> expenseShares.updateExpenseShares(null, Set.of()));
	}

	@Test
	void rejectsNullShareholderIdWhenUpdating() {
		ExpenseSharesFixture fixture = new ExpenseSharesFixture();
		ExpenseShares expenseShares = ExpenseShares.reconstitute(fixture.potId, Set.of());
		Set<ShareholderId> shareholderIds = new HashSet<>();
		shareholderIds.add(null);

		assertThrows(NullPointerException.class, () -> expenseShares.updateExpenseShares(shareholderIds, Set.of()));
	}

	private static final class ExpenseSharesFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId bobId = ShareholderId.of(UUID.randomUUID());

		private ExpenseShare expenseShare(ShareholderId shareholderId, Weight weight) {
			return new ExpenseShare(expenseId, shareholderId, weight);
		}
	}
}
