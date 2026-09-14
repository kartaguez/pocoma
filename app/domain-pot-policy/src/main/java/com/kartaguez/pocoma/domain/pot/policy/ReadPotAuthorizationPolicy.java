package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public final class ReadPotAuthorizationPolicy {

	// TODO: (userId == creatorId || userId in shareholderUserIds) && userPermissions.contains(POT / VIEW)
	public void assertCanReadPot(UserId userId, Set<Permission> userPermissions, UserId creatorId, Set<UserId> shareholderUserIds) {
		LegacyReadAuthorizationSupport.assertPotScoped(userId, userPermissions, creatorId, shareholderUserIds,
				AuthorizationTargetType.POT, PotAction.VIEW_POT, POT_VIEW,
				"Only the pot creator or a shareholder can read the pot",
				"User is missing the required permission to read the pot");
	}

	public void assertCanListReadablePots(UserId userId, Set<Permission> userPermissions) {
		LegacyReadAuthorizationSupport.assertAuthenticated(userId);
		LegacyReadAuthorizationSupport.assertCapability(
				userPermissions, POT_VIEW, "User is missing the required permission to read pots");
	}
}
