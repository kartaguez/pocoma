package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;

public final class ReadPotAuthorizationPolicy {

	private static final Scope REQUIRED_SCOPE = new Scope(Scope.Resource.POT, null, Scope.Action.READ);

	// TODO:  (userId == creatorId || userId in shareholderUserIds) && userScopes.contains(pot:read)
	public void assertCanReadPot(UserId userId, Set<Scope> userScopes, UserId creatorId, Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		Objects.requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");

		assertAuthenticated(userId);

		if (!creatorId.equals(userId) && !shareholderUserIds.contains(userId)) {
			throw new BusinessRuleViolationException(
					"POT_READ_FORBIDDEN",
					"Only the pot creator or a shareholder can read the pot");
		}

		assertHasReadScope(userScopes, "User is missing the required scope to read the pot");
	}

	public void assertCanListReadablePots(UserId userId, Set<Scope> userScopes) {
		Objects.requireNonNull(userScopes, "userScopes must not be null");
		assertAuthenticated(userId);
		assertHasReadScope(userScopes, "User is missing the required scope to read pots");
	}

	private static void assertAuthenticated(UserId userId) {
		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot read the pot");
		}
	}

	private static void assertHasReadScope(Set<Scope> userScopes, String message) {
		if (!userScopes.contains(REQUIRED_SCOPE)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					message);
		}
	}
}
