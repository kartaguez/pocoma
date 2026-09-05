package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_UPDATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class UpdatePotDetailsAuthorizationPolicy {

	// TODO: (userId == creatorId) && userPermissions.contains(POT / UPDATE)
	public void assertCanUpdatePotDetails(UserId userId, Set<Permission> userPermissions, UserId creatorId) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
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

		if (!userPermissions.contains(POT_UPDATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to update pot details");
		}
	}
}
