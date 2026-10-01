package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpdateExpenseSharesCommandTest {

	@Test
	void createsUpdateExpenseSharesCommand() {
		UUID expenseId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		UpdateExpenseSharesCommand.ExpenseShareInput share =
				new UpdateExpenseSharesCommand.ExpenseShareInput(shareholderId, 1, 2);

		UpdateExpenseSharesCommand command = new UpdateExpenseSharesCommand(expenseId, Set.of(share), 3);

		assertEquals(expenseId, command.expenseId());
		assertEquals(Set.of(share), command.shares());
		assertEquals(3, command.expectedVersion());
	}

	@Test
	void rejectsNullExpenseId() {
		assertThrows(NullPointerException.class, () -> new UpdateExpenseSharesCommand(
				null,
				Set.of(new UpdateExpenseSharesCommand.ExpenseShareInput(UUID.randomUUID(), 1, 1)),
				3));
	}

	@Test
	void rejectsEmptyShares() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseSharesCommand(
				UUID.randomUUID(),
				Set.of(),
				3));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseSharesCommand(
				UUID.randomUUID(),
				Set.of(new UpdateExpenseSharesCommand.ExpenseShareInput(UUID.randomUUID(), 1, 1)),
				0));
	}

	@Test
	void rejectsZeroExpenseShareWeight() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseSharesCommand.ExpenseShareInput(
				UUID.randomUUID(),
				0,
				1));
	}

	@Test
	void rejectsNegativeExpenseShareWeight() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseSharesCommand.ExpenseShareInput(
				UUID.randomUUID(),
				-1,
				2));
	}

	@Test
	void rejectsZeroExpenseShareWeightDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseSharesCommand.ExpenseShareInput(
				UUID.randomUUID(),
				1,
				0));
	}
}
