package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.BALANCE_VIEW;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class ReadBalanceAuthorizationPolicy {

	public void assertCanReadBalance(
			UserId userId,
			Set<Permission> userPermissions,
			UserId creatorId,
			Set<UserId> shareholderUserIds) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		Objects.requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");

		assertAuthenticated(userId);

		if (!creatorId.equals(userId) && !shareholderUserIds.contains(userId)) {
			throw new BusinessRuleViolationException(
					"POT_READ_FORBIDDEN",
					"Only the pot creator or a shareholder can read the pot");
		}

		assertHasViewPermission(userPermissions, "User is missing the required permission to read balances");
	}

	public void assertCanListReadableBalances(UserId userId, Set<Permission> userPermissions) {
		Objects.requireNonNull(userPermissions, "userPermissions must not be null");
		assertAuthenticated(userId);
		assertHasViewPermission(userPermissions, "User is missing the required permission to read balances");
	}

	private static void assertAuthenticated(UserId userId) {
		if (userId == null) {
			throw new BusinessRuleViolationException(
					"ANONYMOUS_USER",
					"Anonymous users cannot read the pot");
		}
	}

	private static void assertHasViewPermission(Set<Permission> userPermissions, String message) {
		if (!userPermissions.contains(BALANCE_VIEW)) {
			throw new BusinessRuleViolationException("MISSING_PERMISSION", message);
		}
	}
}
