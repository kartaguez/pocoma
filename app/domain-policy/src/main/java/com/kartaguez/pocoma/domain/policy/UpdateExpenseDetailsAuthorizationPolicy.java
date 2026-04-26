package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

public final class UpdateExpenseDetailsAuthorizationPolicy {

	public void assertCanUpdateExpenseDetails(String userId, UserId creatorId) {
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (!creatorId.value().toString().equals(userId)) {
			throw new BusinessRuleViolationException(
					"EXPENSE_DETAILS_UPDATE_FORBIDDEN",
					"Only the pot creator can update expense details");
		}
	}
}
