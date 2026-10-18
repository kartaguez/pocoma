package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record CreateExpenseContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds,
		Map<ShareholderId, UserId> shareholderUsers) {

	public CreateExpenseContext(PotGlobalVersion version, boolean deleted, UserId creatorId, Set<ShareholderId> ids) {
		this(version, deleted, creatorId, ids, Map.of());
	}

	public CreateExpenseContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
		shareholderUsers = Map.copyOf(Objects.requireNonNull(shareholderUsers, "shareholderUsers must not be null"));
	}

	public void assertCreatePreconditions(
			long observedVersion,
			ShareholderId payerId,
			Set<ShareholderId> expenseShareholderIds) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		Objects.requireNonNull(payerId, "payerId must not be null");
		Set<ShareholderId> checkedExpenseShareholderIds = Set.copyOf(Objects.requireNonNull(
				expenseShareholderIds,
				"expenseShareholderIds must not be null"));

		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Expense cannot be created because the pot is already deleted");
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
