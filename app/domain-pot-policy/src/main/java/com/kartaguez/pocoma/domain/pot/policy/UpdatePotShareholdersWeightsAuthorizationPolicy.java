package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_UPDATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class UpdatePotShareholdersWeightsAuthorizationPolicy {

	// TODO: (userId == creatorId) && userPermissions.contains(SHAREHOLDER / UPDATE)
	public void assertCanUpdatePotShareholdersWeights(UserId userId, Set<Permission> userPermissions, UserId potCreatorId) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
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

		if (!userPermissions.contains(SHAREHOLDER_UPDATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to update shareholders weights");
		}
	}
}
