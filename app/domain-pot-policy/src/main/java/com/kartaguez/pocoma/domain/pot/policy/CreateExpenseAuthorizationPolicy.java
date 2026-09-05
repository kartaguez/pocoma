package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_CREATE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class CreateExpenseAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userPermissions.contains(EXPENSE / CREATE)
	public void assertCanCreateExpense(UserId userId, Set<Permission> userPermissions, UserId potCreatorId, Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
		Objects.requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot create expenses");
		}

		if (!potCreatorId.equals(userId) && !shareholderUserIds.contains(userId)) {
			throw new BusinessRuleViolationException(
					"EXPENSE_CREATE_FORBIDDEN",
					"Only the pot creator or a shareholder can create an expense");
		}

		if (!userPermissions.contains(EXPENSE_CREATE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to create an expense");
		}

	}
}
