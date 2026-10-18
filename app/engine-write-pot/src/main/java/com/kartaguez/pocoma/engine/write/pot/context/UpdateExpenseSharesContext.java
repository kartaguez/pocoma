package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record UpdateExpenseSharesContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		boolean potDeleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds,
		Map<ShareholderId, UserId> shareholderUsers,
		java.util.List<Long> shareStartedAtVersions) {

	public UpdateExpenseSharesContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
		shareholderUsers = Map.copyOf(Objects.requireNonNull(shareholderUsers, "shareholderUsers must not be null"));
		shareStartedAtVersions = java.util.List.copyOf(Objects.requireNonNull(shareStartedAtVersions));
		for (long started : shareStartedAtVersions) {
			if (started < 1 || started > potGlobalVersion.version()) throw new IllegalArgumentException("Invalid share temporal witness");
		}
		if (shareStartedAtVersions.stream().distinct().count() > 1) {
			throw new IllegalArgumentException("Current shares must come from one complete replacement");
		}
	}

	public void assertUpdatePreconditions(long observedVersion, Set<ShareholderId> expenseShareholderIds) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		Set<ShareholderId> checkedExpenseShareholderIds = Set.copyOf(Objects.requireNonNull(
				expenseShareholderIds,
				"expenseShareholderIds must not be null"));
		if (potDeleted) throw new BusinessRuleViolationException("POT_ALREADY_DELETED",
				"Expense shares cannot be updated because the pot is already deleted");

		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_ALREADY_DELETED",
					"Expense shares cannot be updated because the expense is already deleted");
		}

		if ((shareStartedAtVersions.isEmpty() && observedVersion != potGlobalVersion.version())
				|| shareStartedAtVersions.stream().anyMatch(started -> started > observedVersion)) {
			throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A relevant object changed since the observed version");
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
