package com.kartaguez.pocoma.engine.context;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public record DeleteExpenseContext(PotGlobalVersion potGlobalVersion, boolean deleted, UserId creatorId) {

	public DeleteExpenseContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}

	public void assertDeletePreconditions(long expectedVersion) {
		if (deleted) {
			throw new BusinessRuleViolationException(
					"EXPENSE_ALREADY_DELETED",
					"Expense cannot be deleted because it is already deleted");
		}

		if (expectedVersion != potGlobalVersion.version()) {
			throw new VersionConflictException("Expense cannot be deleted because the expected version is not active");
		}
	}
}
