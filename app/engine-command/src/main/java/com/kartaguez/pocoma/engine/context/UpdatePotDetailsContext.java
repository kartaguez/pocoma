package com.kartaguez.pocoma.engine.context;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public record UpdatePotDetailsContext(PotGlobalVersion potGlobalVersion, boolean deleted, UserId creatorId) {

	public UpdatePotDetailsContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}

	public void assertUpdatePreconditions(long expectedVersion) {
		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Pot details cannot be updated because the pot is already deleted");
		}

		if (expectedVersion != potGlobalVersion.version()) {
			throw new VersionConflictException("Pot details cannot be updated because the expected version is not active");
		}
	}
}
