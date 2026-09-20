package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class UpdateExpenseDetailsCommandTest {

	@Test
	void createsUpdateExpenseDetailsCommand() {
		UUID expenseId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		LocalDate date = LocalDate.parse("2026-02-02");

		UpdateExpenseDetailsCommand command = new UpdateExpenseDetailsCommand(
				expenseId,
				payerId,
				42,
				1,
				"Dinner",
				date,
				3);

		assertEquals(expenseId, command.expenseId());
		assertEquals(payerId, command.payerId());
		assertEquals("Dinner", command.label());
		assertEquals(date, command.date());
		assertEquals(3, command.expectedVersion());
	}

	@Test
	void rejectsMissingBusinessDate() {
		assertThrows(NullPointerException.class, () -> new UpdateExpenseDetailsCommand(
				UUID.randomUUID(), UUID.randomUUID(), 42, 1, "Dinner", null, 3));
	}

	@Test
	void rejectsNullExpenseId() {
		assertThrows(NullPointerException.class, () -> new UpdateExpenseDetailsCommand(
				null,
				UUID.randomUUID(),
				42,
				1,
				"Dinner",
				LocalDate.parse("2026-01-01"),
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
				LocalDate.parse("2026-01-01"),
				0));
	}

	@Test
	void rejectsNegativeAmount() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseDetailsCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				-1,
				1,
				"Dinner",
				LocalDate.parse("2026-01-01"),
				3));
	}

	@Test
	void rejectsZeroAmountDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateExpenseDetailsCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				42,
				0,
				"Dinner",
				LocalDate.parse("2026-01-01"),
				3));
	}
}
