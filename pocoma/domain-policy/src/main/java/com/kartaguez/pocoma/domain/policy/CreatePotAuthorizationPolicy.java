package com.kartaguez.pocoma.domain.policy;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;

public final class CreatePotAuthorizationPolicy {

	public void assertCanCreatePot(String userId) {
		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous user cannot create a pot");
		}
	}
}
