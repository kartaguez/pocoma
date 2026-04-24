package com.kartaguez.pocoma.domain.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ExpenseShareDraftTest {

	@Test
	void createsExpenseShareDraft() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Weight weight = Weight.of(new Fraction(1, 2));

		ExpenseShareDraft expenseShareDraft = new ExpenseShareDraft(shareholderId, weight);

		assertEquals(shareholderId, expenseShareDraft.shareholderId());
		assertEquals(weight, expenseShareDraft.weight());
	}

	@Test
	void rejectsNullShareholderId() {
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> new ExpenseShareDraft(null, weight));
	}

	@Test
	void rejectsNullWeight() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new ExpenseShareDraft(shareholderId, null));
	}
}
