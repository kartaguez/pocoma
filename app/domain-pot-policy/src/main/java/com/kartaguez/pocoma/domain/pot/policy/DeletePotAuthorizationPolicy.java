package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import java.util.Set;

public final class DeletePotAuthorizationPolicy {

	// TODO: (userId == creatorId) && userScopes.contains(pot:delete)
	public void assertCanDeletePot(UserId userId, Set<Scope> userScopes, UserId creatorId) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot delete the pot");
		}

		if (!creatorId.equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_DELETE_FORBIDDEN",
					"Only the pot creator can delete the pot");
		}
		Scope requiredScope = new Scope(Scope.Resource.POT, null, Scope.Action.DELETE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to delete the pot");
		}

	}
}
