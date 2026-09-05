package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_UPDATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class UpdatePotShareholdersDetailsAuthorizationPolicy {

	// TODO: (userId == creatorId || userId == shareholder.userId) && userPermissions.contains(SHAREHOLDER / UPDATE)
	public void assertCanUpdatePotShareholdersDetails(UserId userId, Set<Permission> userPermissions, UserId creatorId, UserId shareholderUserId) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
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
		if (!userPermissions.contains(SHAREHOLDER_UPDATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to update a shareholder's details");
		}
	}
}
