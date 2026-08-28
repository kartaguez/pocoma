package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.policy.scope.Scope;

public final class CreatePotAuthorizationPolicy {

	// TODO: userId != null && userScopes.contains(pot:create)
	public void assertCanCreatePot(String userId, Set<Scope> userScopes) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous user cannot create a pot");
		}
		Scope requiredScope = new Scope(Scope.Resource.POT, null, Scope.Action.CREATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to create a pot");
		}
	}
}
