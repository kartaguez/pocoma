package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record UpdatePotDetailsContext(PotGlobalVersion potGlobalVersion, boolean deleted, UserId creatorId,
		long headerStartedAtVersion) {

	public UpdatePotDetailsContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		if (headerStartedAtVersion < 1 || headerStartedAtVersion > potGlobalVersion.version()) {
			throw new IllegalArgumentException("Invalid header temporal witness");
		}
	}

	public void assertUpdatePreconditions(long observedVersion) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Pot details cannot be updated because the pot is already deleted");
		}

		if (headerStartedAtVersion > observedVersion) {
			throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A relevant object changed since the observed version");
		}

	}
}
