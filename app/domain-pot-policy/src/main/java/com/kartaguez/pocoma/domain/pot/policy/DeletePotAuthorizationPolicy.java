package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_DELETE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class DeletePotAuthorizationPolicy {

	// TODO: (userId == creatorId) && userPermissions.contains(POT / DELETE)
	public void assertCanDeletePot(UserId userId, Set<Permission> userPermissions, UserId creatorId) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
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
		if (!userPermissions.contains(POT_DELETE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to delete the pot");
		}

	}
}
