package com.kartaguez.pocoma.domain.pot.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.created.ExpenseCreated;
import com.kartaguez.pocoma.domain.pot.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class ExpenseFactoryTest {

	@Test
	void createsExpense() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		Amount amount = Amount.of(Fraction.of(42, 1));
		Label label = Label.of("Dinner");
		Set<ExpenseShareDraft> shares = Set.of(new ExpenseShareDraft(payerId, Weight.of(Fraction.of(1, 1))));

		ExpenseCreated expenseCreated = ExpenseFactory.createExpense(potId, payerId, amount, label, shares);

		assertNotNull(expenseCreated.id());
		assertEquals(potId, expenseCreated.potId());
		assertEquals(payerId, expenseCreated.payerId());
		assertEquals(amount, expenseCreated.amount());
		assertEquals(label, expenseCreated.label());
		assertEquals(shares, expenseCreated.shares());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> ExpenseFactory.createExpense(
				null,
				ShareholderId.of(UUID.randomUUID()),
				Amount.of(Fraction.of(42, 1)),
				Label.of("Dinner"),
				Set.of()));
	}
}
