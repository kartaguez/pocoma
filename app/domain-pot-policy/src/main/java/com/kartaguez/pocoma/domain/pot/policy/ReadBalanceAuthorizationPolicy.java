package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.BALANCE_VIEW;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class ReadBalanceAuthorizationPolicy {

	public void assertCanReadBalance(
			UserId userId,
			Set<Permission> userPermissions,
			UserId creatorId,
			Set<UserId> shareholderUserIds) {
		LegacyReadAuthorizationSupport.assertPotScoped(userId, userPermissions, creatorId, shareholderUserIds,
				AuthorizationTargetType.POT, PotAction.VIEW_BALANCE, BALANCE_VIEW,
				"Only the pot creator or a shareholder can read the pot",
				"User is missing the required permission to read balances");
	}

	public void assertCanListReadableBalances(UserId userId, Set<Permission> userPermissions) {
		LegacyReadAuthorizationSupport.assertAuthenticated(userId);
		LegacyReadAuthorizationSupport.assertCapability(
				userPermissions, BALANCE_VIEW, "User is missing the required permission to read balances");
	}
}
