package com.kartaguez.pocoma.engine.write.pot.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

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
	void rejectsObsoleteCommand() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		UpdateExpenseSharesContext context = context(false, Set.of(shareholderId));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(2, Set.of(shareholderId)));

		assertEquals("OBSOLETE_COMMAND", exception.ruleCode());
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
				false,
				UserId.of(UUID.randomUUID()),
				shareholderIds,
				java.util.Map.of(),
				java.util.List.of(3L));
	}
}
