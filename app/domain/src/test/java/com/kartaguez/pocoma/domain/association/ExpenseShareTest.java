package com.kartaguez.pocoma.domain.association;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ExpenseShareTest {

	@Test
	void createsExpenseShare() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Weight weight = Weight.of(new Fraction(1, 2));

		ExpenseShare expenseShare = new ExpenseShare(expenseId, shareholderId, weight);

		assertEquals(expenseId, expenseShare.expenseId());
		assertEquals(shareholderId, expenseShare.shareholderId());
		assertEquals(weight, expenseShare.weight());
	}

	@Test
	void rejectsNullExpenseId() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> new ExpenseShare(null, shareholderId, weight));
	}

	@Test
	void rejectsNullShareholderId() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> new ExpenseShare(expenseId, null, weight));
	}

	@Test
	void rejectsNullWeight() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new ExpenseShare(expenseId, shareholderId, null));
	}
}
