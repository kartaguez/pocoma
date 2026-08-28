package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class UpdatePotShareholdersWeightsAuthorizationPolicy {

	// TODO: (userId == creatorId) && userScopes.contains(shareholder.weight:update)
	public void assertCanUpdatePotShareholdersWeights(UserId userId, Set<Scope> userScopes, UserId potCreatorId) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot update shareholders weights");
		}

		if (!potCreatorId.equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN",
					"Only the pot creator can update shareholders weights");
		}

		Scope requiredScope = new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.WEIGHT, Scope.Action.UPDATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to update shareholders weights");
		}
	}
}
