package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;

public final class UpdatePotShareholdersDetailsAuthorizationPolicy {

	// TODO: (userId == creatorId || userId == shareholder.userId) && userScopes.contains(shareholder.details:update)
	public void assertCanUpdatePotShareholdersDetails(UserId userId, Set<Scope> userScopes, UserId creatorId, UserId shareholderUserId) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot update shareholder details");
		}
	
		if (!creatorId.equals(userId) && (shareholderUserId == null || !shareholderUserId.equals(userId))) {
			throw new BusinessRuleViolationException(
					"POT_SHAREHOLDERS_DETAILS_UPDATE_FORBIDDEN",
					"Only the pot creator or the shareholder themselves can update a shareholder's details");
		}
		Scope requiredScope = new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.DETAILS, Scope.Action.UPDATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to update a shareholder's details");
		}
	}
}
