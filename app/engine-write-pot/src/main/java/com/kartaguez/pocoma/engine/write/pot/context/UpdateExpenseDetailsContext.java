package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record UpdateExpenseDetailsContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		boolean potDeleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds,
		Map<ShareholderId, UserId> shareholderUsers,
		long headerStartedAtVersion) {

	public UpdateExpenseDetailsContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
		shareholderUsers = Map.copyOf(Objects.requireNonNull(shareholderUsers, "shareholderUsers must not be null"));
		if (headerStartedAtVersion < 1 || headerStartedAtVersion > potGlobalVersion.version()) {
			throw new IllegalArgumentException("Invalid header temporal witness");
		}
	}

	public void assertUpdatePreconditions(long observedVersion, ShareholderId payerId) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		Objects.requireNonNull(payerId, "payerId must not be null");
		if (potDeleted) throw new BusinessRuleViolationException("POT_ALREADY_DELETED",
				"Expense details cannot be updated because the pot is already deleted");

		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_ALREADY_DELETED",
					"Expense details cannot be updated because the expense is already deleted");
		}

		if (headerStartedAtVersion > observedVersion) {
			throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A relevant object changed since the observed version");
		}

		if (!shareholderIds.contains(payerId)) {
			throw new BusinessRuleViolationException(
					"SHAREHOLDER_NOT_PRESENT",
					"Expense payer does not belong to this pot");
		}
	}
}
