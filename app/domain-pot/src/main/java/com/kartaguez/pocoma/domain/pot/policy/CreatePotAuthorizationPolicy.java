package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_CREATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;

public final class CreatePotAuthorizationPolicy {

	// TODO: userId != null && userPermissions.contains(POT / CREATE)
	public void assertCanCreatePot(String userId, Set<Permission> userPermissions) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous user cannot create a pot");
		}
		if (!userPermissions.contains(POT_CREATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to create a pot");
		}
	}
}
