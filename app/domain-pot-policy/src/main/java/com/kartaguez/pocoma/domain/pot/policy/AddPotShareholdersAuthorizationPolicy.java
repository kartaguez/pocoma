package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_CREATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class AddPotShareholdersAuthorizationPolicy {

	// TODO: (userId == creatorId) && userPermissions.contains(SHAREHOLDER / CREATE)
	public void assertCanAddPotShareholders(UserId userId, Set<Permission> userPermissions, UserId creatorId) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
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
		if (!userPermissions.contains(SHAREHOLDER_CREATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to add shareholders");
		}

	}
}
