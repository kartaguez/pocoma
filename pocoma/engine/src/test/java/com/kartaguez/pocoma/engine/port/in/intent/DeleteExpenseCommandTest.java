package com.kartaguez.pocoma.engine.port.in.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeleteExpenseCommandTest {

	@Test
	void createsDeleteExpenseCommand() {
		UUID expenseId = UUID.randomUUID();

		DeleteExpenseCommand command = new DeleteExpenseCommand(expenseId, 1);

		assertEquals(expenseId, command.expenseId());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullExpenseId() {
		assertThrows(NullPointerException.class, () -> new DeleteExpenseCommand(null, 1));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new DeleteExpenseCommand(UUID.randomUUID(), 0));
	}
}
