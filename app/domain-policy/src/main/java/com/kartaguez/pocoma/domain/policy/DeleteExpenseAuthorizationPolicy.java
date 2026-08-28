package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import java.util.Set;

public final class DeleteExpenseAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userScopes.contains(expense:delete)
	public void assertCanDeleteExpense(UserId userId, Set<Scope> userScopes, Set<UserId> shareholderUserIds, UserId potCreatorId) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userScopes, "userScopes must not be null");
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

		Scope requiredScope = new Scope(Scope.Resource.EXPENSE, null, Scope.Action.DELETE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to delete an expense");
		}
	}
}
