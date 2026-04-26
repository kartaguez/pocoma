package com.kartaguez.pocoma.engine.context;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public record UpdateExpenseSharesContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds) {

	public UpdateExpenseSharesContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
	}

	public void assertUpdatePreconditions(long expectedVersion, Set<ShareholderId> expenseShareholderIds) {
		Set<ShareholderId> checkedExpenseShareholderIds = Set.copyOf(Objects.requireNonNull(
				expenseShareholderIds,
				"expenseShareholderIds must not be null"));

		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_ALREADY_DELETED",
					"Expense shares cannot be updated because the expense is already deleted");
		}

		if (expectedVersion != potGlobalVersion.version()) {
			throw new VersionConflictException("Expense shares cannot be updated because the expected version is not active");
		}

		for (ShareholderId shareholderId : checkedExpenseShareholderIds) {
			if (!shareholderIds.contains(shareholderId)) {
				throw new BusinessRuleViolationException(
						"SHAREHOLDER_NOT_PRESENT",
						"Expense share references a shareholder that does not belong to this pot");
			}
		}
	}
}
