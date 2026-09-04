package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpdateExpenseDetailsCommandTest {

	@Test
	void createsUpdateExpenseDetailsCommand() {
		UUID expenseId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();

		UpdateExpenseDetailsCommand command = new UpdateExpenseDetailsCommand(
				expenseId,
				payerId,
				42,
				1,
				"Dinner",
				3);

		assertEquals(expenseId, command.expenseId());
		assertEquals(payerId, command.payerId());
		assertEquals("Dinner", command.label());
		assertEquals(3, command.expectedVersion());
	}

	@Test
	void rejectsNullExpenseId() {
		assertThrows(NullPointerException.class, () -> new UpdateExpenseDetailsCommand(
				null,
				UUID.randomUUID(),
				42,
				1,
				"Dinner",
				3));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseDetailsCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				42,
				1,
				"Dinner",
				0));
	}
}
