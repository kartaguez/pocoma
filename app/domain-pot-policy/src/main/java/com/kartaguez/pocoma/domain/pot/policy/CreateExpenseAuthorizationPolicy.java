package com.kartaguez.pocoma.domain.pot.policy;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import java.util.Set;

public final class CreateExpenseAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userScopes.contains(expense:create)
	public void assertCanCreateExpense(UserId userId, Set<Scope> userScopes, UserId potCreatorId, Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userScopes, "userScopes must not be null");
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

		Scope requiredScope = new Scope(Scope.Resource.EXPENSE, null, Scope.Action.CREATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to create an expense");
		}

	}
}
