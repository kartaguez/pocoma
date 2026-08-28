package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;

public final class UpdatePotDetailsAuthorizationPolicy {

	// TODO: (userId == creatorId) && userScopes.contains(pot.details:update)
	public void assertCanUpdatePotDetails(UserId userId, Set<Scope> userScopes, UserId creatorId) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null"); 

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot update pot details");
		}

		if (!creatorId.equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_DETAILS_UPDATE_FORBIDDEN",
					"Only the pot creator can update pot details");
		}

		Scope requiredScope = new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to update pot details");
		}
	}
}
