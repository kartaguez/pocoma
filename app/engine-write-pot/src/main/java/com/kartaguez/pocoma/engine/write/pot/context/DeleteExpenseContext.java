package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;
import java.util.Map;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record DeleteExpenseContext(PotGlobalVersion potGlobalVersion, boolean deleted, boolean potDeleted,
		UserId creatorId, Map<ShareholderId, UserId> shareholderUsers,
		long headerStartedAtVersion, java.util.List<Long> shareStartedAtVersions) {

	public DeleteExpenseContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderUsers = Map.copyOf(Objects.requireNonNull(shareholderUsers, "shareholderUsers must not be null"));
		if (headerStartedAtVersion < 1 || headerStartedAtVersion > potGlobalVersion.version()) {
			throw new IllegalArgumentException("Invalid header temporal witness");
		}
		shareStartedAtVersions = java.util.List.copyOf(Objects.requireNonNull(shareStartedAtVersions));
		for (long started : shareStartedAtVersions) {
			if (started < 1 || started > potGlobalVersion.version()) throw new IllegalArgumentException("Invalid share temporal witness");
		}
		if (shareStartedAtVersions.stream().distinct().count() > 1) {
			throw new IllegalArgumentException("Current shares must come from one complete replacement");
		}
	}

	public void assertDeletePreconditions(long observedVersion) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		if (potDeleted) {
			throw new BusinessRuleViolationException("POT_ALREADY_DELETED",
					"Expense cannot be deleted because the pot is already deleted");
		}
		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_ALREADY_DELETED",
					"Expense cannot be deleted because it is already deleted");
		}

		if (headerStartedAtVersion > observedVersion
				|| (shareStartedAtVersions.isEmpty() && observedVersion != potGlobalVersion.version())
				|| shareStartedAtVersions.stream().anyMatch(started -> started > observedVersion)) {
			throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A relevant object changed since the observed version");
		}

	}
}
