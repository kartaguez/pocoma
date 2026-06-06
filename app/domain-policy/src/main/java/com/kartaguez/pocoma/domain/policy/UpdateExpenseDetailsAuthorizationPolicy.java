package com.kartaguez.pocoma.domain.policy;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.policy.scope.Scope;

public final class UpdateExpenseDetailsAuthorizationPolicy {

	// TODO: (userId in shareholders.userId) && userScopes.contains(expense.details:update)
	public void assertCanUpdateExpenseDetails(UserId userId, Set<Scope> userScopes, UserId potCreatorId, Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(potCreatorId, "potCreatorId must not be null");
		Objects.requireNonNull(userScopes, "userScopes must not be null");
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

		Scope requiredScope = new Scope(Scope.Resource.EXPENSE, Scope.SubResource.DETAILS, Scope.Action.UPDATE);
		if (!userScopes.contains(requiredScope)) {
			throw new BusinessRuleViolationException(
					"MISSING_SCOPE",
					"User is missing the required scope to update expense details");
		}
	}
}
