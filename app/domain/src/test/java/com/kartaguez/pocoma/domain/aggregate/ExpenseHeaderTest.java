package com.kartaguez.pocoma.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ExpenseHeaderTest {

	@Test
	void reconstitutesExpenseHeader() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		ExpenseHeader expenseHeader = ExpenseHeader.reconstitute(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				false);

		assertEquals(fixture.id, expenseHeader.id());
		assertEquals(fixture.potId, expenseHeader.potId());
		assertEquals(fixture.payerId, expenseHeader.payerId());
		assertEquals(fixture.amount, expenseHeader.amount());
		assertEquals(fixture.label, expenseHeader.label());
		assertFalse(expenseHeader.deleted());
	}

	@Test
	void reconstitutesDeletedExpenseHeader() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		ExpenseHeader expenseHeader = ExpenseHeader.reconstitute(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				true);

		assertTrue(expenseHeader.deleted());
	}

	@Test
	void marksAsDeleted() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.expenseHeader();

		expenseHeader.markAsDeleted();

		assertTrue(expenseHeader.deleted());
	}

	@Test
	void updatesDetails() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.expenseHeader();
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		Amount amount = Amount.of(new Fraction(84, 1));
		Label label = Label.of("Updated dinner");

		expenseHeader.updateDetails(payerId, amount, label);

		assertEquals(payerId, expenseHeader.payerId());
		assertEquals(amount, expenseHeader.amount());
		assertEquals(label, expenseHeader.label());
	}

	@Test
	void rejectsNullPayerIdWhenUpdatingDetails() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.expenseHeader();

		assertThrows(NullPointerException.class, () -> expenseHeader.updateDetails(null, fixture.amount, fixture.label));
	}

	@Test
	void rejectsNullAmountWhenUpdatingDetails() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.expenseHeader();

		assertThrows(NullPointerException.class, () -> expenseHeader.updateDetails(fixture.payerId, null, fixture.label));
	}

	@Test
	void rejectsNullLabelWhenUpdatingDetails() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.expenseHeader();

		assertThrows(NullPointerException.class, () -> expenseHeader.updateDetails(fixture.payerId, fixture.amount, null));
	}

	@Test
	void rejectsMarkAsDeletedWhenDeleted() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.deletedExpenseHeader();

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				expenseHeader::markAsDeleted);

		assertEquals("EXPENSE_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsUpdateDetailsWhenDeleted() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();
		ExpenseHeader expenseHeader = fixture.deletedExpenseHeader();

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> expenseHeader.updateDetails(
						ShareholderId.of(UUID.randomUUID()),
						Amount.of(new Fraction(84, 1)),
						Label.of("Updated dinner")));

		assertEquals("EXPENSE_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsNullId() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		assertThrows(NullPointerException.class, () -> ExpenseHeader.reconstitute(
				null,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				false));
	}

	@Test
	void rejectsNullPotId() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		assertThrows(NullPointerException.class, () -> ExpenseHeader.reconstitute(
				fixture.id,
				null,
				fixture.payerId,
				fixture.amount,
				fixture.label,
				false));
	}

	@Test
	void rejectsNullPayerId() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		assertThrows(NullPointerException.class, () -> ExpenseHeader.reconstitute(
				fixture.id,
				fixture.potId,
				null,
				fixture.amount,
				fixture.label,
				false));
	}

	@Test
	void rejectsNullAmount() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		assertThrows(NullPointerException.class, () -> ExpenseHeader.reconstitute(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				null,
				fixture.label,
				false));
	}

	@Test
	void rejectsNullLabel() {
		ExpenseHeaderFixture fixture = new ExpenseHeaderFixture();

		assertThrows(NullPointerException.class, () -> ExpenseHeader.reconstitute(
				fixture.id,
				fixture.potId,
				fixture.payerId,
				fixture.amount,
				null,
				false));
	}

	private static final class ExpenseHeaderFixture {
		private final ExpenseId id = ExpenseId.of(UUID.randomUUID());
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final Amount amount = Amount.of(new Fraction(42, 1));
		private final Label label = Label.of("Dinner");

		private ExpenseHeader expenseHeader() {
			return ExpenseHeader.reconstitute(id, potId, payerId, amount, label, false);
		}

		private ExpenseHeader deletedExpenseHeader() {
			return ExpenseHeader.reconstitute(id, potId, payerId, amount, label, true);
		}
	}
}
