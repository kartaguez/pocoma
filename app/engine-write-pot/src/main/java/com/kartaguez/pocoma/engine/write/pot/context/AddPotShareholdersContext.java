package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record AddPotShareholdersContext(PotGlobalVersion potGlobalVersion, boolean deleted, UserId creatorId) {

	public AddPotShareholdersContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}

	public void assertAddPreconditions(long observedVersion) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Shareholders cannot be added because the pot is already deleted");
		}

	}
}
