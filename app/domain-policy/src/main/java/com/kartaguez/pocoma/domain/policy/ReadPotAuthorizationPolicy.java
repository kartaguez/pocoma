package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

public final class ReadPotAuthorizationPolicy {

	public void assertCanReadPot(String userId, UserId creatorId, boolean linkedShareholder) {
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (creatorId.value().toString().equals(userId) || linkedShareholder) {
			return;
		}

		throw new BusinessRuleViolationException(
				"POT_READ_FORBIDDEN",
				"Only the pot creator or a linked shareholder can read this pot");
	}
}
