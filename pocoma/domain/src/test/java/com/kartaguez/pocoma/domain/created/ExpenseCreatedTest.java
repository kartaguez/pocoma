package com.kartaguez.pocoma.domain.created;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ExpenseCreatedTest {

	@Test
	void createsExpenseCreated() {
		ExpenseId id = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		Amount amount = Amount.of(new Fraction(42, 1));
		Label label = Label.of("Dinner");
		Set<ExpenseShareDraft> shares = Set.of(new ExpenseShareDraft(payerId, Weight.of(new Fraction(1, 1))));

		ExpenseCreated expenseCreated = new ExpenseCreated(id, potId, payerId, amount, label, shares);

		assertEquals(id, expenseCreated.id());
		assertEquals(potId, expenseCreated.potId());
		assertEquals(payerId, expenseCreated.payerId());
		assertEquals(amount, expenseCreated.amount());
		assertEquals(label, expenseCreated.label());
		assertEquals(shares, expenseCreated.shares());
	}

	@Test
	void rejectsNullId() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				null,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				fixture.shares));
	}

	@Test
	void rejectsNullPotId() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				null,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				fixture.shares));
	}

	@Test
	void rejectsNullPayerId() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				fixture.potId,
				null,
				fixture.amount,
				fixture.label,
				fixture.shares));
	}

	@Test
	void rejectsNullAmount() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				null,
				fixture.label,
				fixture.shares));
	}

	@Test
	void rejectsNullLabel() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				null,
				fixture.shares));
	}

	@Test
	void rejectsNullShares() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				null));
	}

	@Test
	void rejectsNullShare() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();
		Set<ExpenseShareDraft> shares = new java.util.HashSet<>();
		shares.add(null);

		assertThrows(NullPointerException.class, () -> new ExpenseCreated(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				shares));
	}

	@Test
	void copiesShares() {
		ExpenseCreatedFixture fixture = new ExpenseCreatedFixture();
		Set<ExpenseShareDraft> shares = new java.util.HashSet<>(fixture.shares);

		ExpenseCreated expenseCreated = new ExpenseCreated(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				shares);
		shares.clear();

		assertEquals(fixture.shares, expenseCreated.shares());
	}

	private static final class ExpenseCreatedFixture {
		private final ExpenseId id = ExpenseId.of(UUID.randomUUID());
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final Amount amount = Amount.of(new Fraction(42, 1));
		private final Label label = Label.of("Dinner");
		private final Set<ExpenseShareDraft> shares = Set.of(new ExpenseShareDraft(payerId, Weight.of(new Fraction(1, 1))));
	}
}
