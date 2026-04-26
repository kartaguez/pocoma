package com.kartaguez.pocoma.domain.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExpenseIdTest {

	@Test
	void createsExpenseIdFromUuid() {
		UUID value = UUID.randomUUID();

		ExpenseId expenseId = ExpenseId.of(value);

		assertEquals(value, expenseId.value());
		assertEquals(new ExpenseId(value), expenseId);
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> ExpenseId.of(null));
	}
}
