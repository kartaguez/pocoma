package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

public final class UpdatePotShareholdersWeightsAuthorizationPolicy {

	public void assertCanUpdatePotShareholdersWeights(String userId, UserId creatorId) {
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (!creatorId.value().toString().equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN",
					"Only the pot creator can update shareholders weights");
		}
	}
}
