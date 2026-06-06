package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.policy.scope.Scope;

public final class AddPotShareholdersAuthorizationPolicy {

	// TODO: (userId == creatorId) && userScopes.contains(shareholder:create)
	public void assertCanAddPotShareholders(UserId userId, Set<Scope> userScopes, UserId creatorId) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot add shareholders");
		}

		if (!creatorId.equals(userId)) {
			throw new BusinessRuleViolationException(
					"POT_SHAREHOLDERS_ADD_FORBIDDEN",
					"Only the pot creator can add shareholders");
		}
		Scope requiredScope = new Scope(Scope.Resource.SHAREHOLDER, null, Scope.Action.CREATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to add shareholders");
		}

	}
}
