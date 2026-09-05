package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_DELETE;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class DeleteExpenseAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userPermissions.contains(EXPENSE / DELETE)
	public void assertCanDeleteExpense(UserId userId, Set<Permission> userPermissions, Set<UserId> shareholderUserIds, UserId potCreatorId) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
		Objects.requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");

		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot delete expenses");
		}

		if (!potCreatorId.equals(userId) && !shareholderUserIds.contains(userId)) {
			throw new BusinessRuleViolationException(
					"EXPENSE_DELETE_FORBIDDEN",
					"Only the pot creator or a shareholder can delete an expense");
		}

		if (!userPermissions.contains(EXPENSE_DELETE)) {
			throw new BusinessRuleViolationException(
					"MISSING_PERMISSION",
					"User is missing the required permission to delete an expense");
		}
	}
}
