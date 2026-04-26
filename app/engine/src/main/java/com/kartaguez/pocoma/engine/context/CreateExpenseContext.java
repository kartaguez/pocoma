package com.kartaguez.pocoma.engine.context;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public record CreateExpenseContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds) {

	public CreateExpenseContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
	}

	public void assertCreatePreconditions(
			long expectedVersion,
			ShareholderId payerId,
			Set<ShareholderId> expenseShareholderIds) {
		Objects.requireNonNull(payerId, "payerId must not be null");
		Set<ShareholderId> checkedExpenseShareholderIds = Set.copyOf(Objects.requireNonNull(
				expenseShareholderIds,
				"expenseShareholderIds must not be null"));

		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Expense cannot be created because the pot is already deleted");
		}

		if (expectedVersion != potGlobalVersion.version()) {
			throw new VersionConflictException("Expense cannot be created because the expected version is not active");
		}

		if (!shareholderIds.contains(payerId)) {
			throw new BusinessRuleViolationException(
					"SHAREHOLDER_NOT_PRESENT",
					"Expense payer does not belong to this pot");
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
