package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

public final class DeletePotAuthorizationPolicy {

	public void assertCanDeletePot(String userId, UserId creatorId) {
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		if (!creatorId.value().toString().equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_DELETE_FORBIDDEN",
					"Only the pot creator can delete the pot");
		}
	}
}
