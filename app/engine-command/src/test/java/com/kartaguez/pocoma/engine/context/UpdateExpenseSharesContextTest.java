package com.kartaguez.pocoma.engine.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

class UpdateExpenseSharesContextTest {

	@Test
	void acceptsValidPreconditions() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		UpdateExpenseSharesContext context = context(false, Set.of(shareholderId));

		assertDoesNotThrow(() -> context.assertUpdatePreconditions(3, Set.of(shareholderId)));
	}

	@Test
	void rejectsAlreadyDeletedExpense() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		UpdateExpenseSharesContext context = context(true, Set.of(shareholderId));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(3, Set.of(shareholderId)));

		assertEquals("EXPENSE_ALREADY_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsVersionConflict() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		UpdateExpenseSharesContext context = context(false, Set.of(shareholderId));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> context.assertUpdatePreconditions(2, Set.of(shareholderId)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
	}

	@Test
	void rejectsUnknownShareholder() {
		UpdateExpenseSharesContext context = context(false, Set.of(ShareholderId.of(UUID.randomUUID())));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(3, Set.of(ShareholderId.of(UUID.randomUUID()))));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	private static UpdateExpenseSharesContext context(boolean deleted, Set<ShareholderId> shareholderIds) {
		return new UpdateExpenseSharesContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 3),
				deleted,
				UserId.of(UUID.randomUUID()),
				shareholderIds);
	}
}
