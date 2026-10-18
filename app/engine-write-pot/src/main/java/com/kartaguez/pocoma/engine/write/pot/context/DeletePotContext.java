package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record DeletePotContext(PotGlobalVersion potGlobalVersion, boolean deleted, UserId creatorId) {

	public DeletePotContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}

	public void assertDeletePreconditions(long observedVersion) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Pot cannot be deleted because it is already deleted");
		}
		if (observedVersion != potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A relevant object changed since the observed version");
		}

	}
}
