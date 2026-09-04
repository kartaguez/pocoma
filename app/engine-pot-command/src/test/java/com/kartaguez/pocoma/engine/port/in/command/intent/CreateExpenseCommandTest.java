package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CreateExpenseCommandTest {

	@Test
	void createsCreateExpenseCommand() {
		UUID potId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		CreateExpenseCommand.ExpenseShareInput share =
				new CreateExpenseCommand.ExpenseShareInput(UUID.randomUUID(), 1, 2);

		CreateExpenseCommand command = new CreateExpenseCommand(potId, payerId, 42, 1, "Dinner", Set.of(share), 3);

		assertEquals(potId, command.potId());
		assertEquals(payerId, command.payerId());
		assertEquals("Dinner", command.label());
		assertEquals(Set.of(share), command.shares());
		assertEquals(3, command.expectedVersion());
	}

	@Test
	void rejectsEmptyShares() {
		assertThrows(IllegalArgumentException.class, () -> new CreateExpenseCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				42,
				1,
				"Dinner",
				Set.of(),
				3));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new CreateExpenseCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				42,
				1,
				"Dinner",
				Set.of(new CreateExpenseCommand.ExpenseShareInput(UUID.randomUUID(), 1, 1)),
				0));
	}

	@Test
	void rejectsZeroExpenseShareWeight() {
		assertThrows(IllegalArgumentException.class, () -> new CreateExpenseCommand.ExpenseShareInput(
				UUID.randomUUID(),
				0,
				1));
	}

	@Test
	void rejectsNegativeExpenseShareWeight() {
		assertThrows(IllegalArgumentException.class, () -> new CreateExpenseCommand.ExpenseShareInput(
				UUID.randomUUID(),
				-1,
				2));
	}

	@Test
	void rejectsZeroExpenseShareWeightDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new CreateExpenseCommand.ExpenseShareInput(
				UUID.randomUUID(),
				1,
				0));
	}
}
