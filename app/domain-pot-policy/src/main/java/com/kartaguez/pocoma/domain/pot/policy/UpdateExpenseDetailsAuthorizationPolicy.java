package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_UPDATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class UpdateExpenseDetailsAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userPermissions.contains(EXPENSE / UPDATE)
	public void assertCanUpdateExpenseDetails(UserId userId, Set<Permission> userPermissions, UserId potCreatorId, Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
		Objects.requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot update expense details");
		}

		if (!potCreatorId.equals(userId) && !shareholderUserIds.contains(userId)) {
			throw new BusinessRuleViolationException(
					"EXPENSE_DETAILS_UPDATE_FORBIDDEN",
					"Only the pot creator or a shareholder can update expense details");
		}

		if (!userPermissions.contains(EXPENSE_UPDATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to update expense details");
		}
	}
}
