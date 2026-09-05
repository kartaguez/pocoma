package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Permission;
import com.kartaguez.pocoma.engine.security.UserContext;

final class PotAuthorizationSnapshotMapper {

	private static final Map<Permission, Scope> SCOPES_BY_PERMISSION = Map.ofEntries(
			entry("POT", "CREATE", Scope.Resource.POT, null, Scope.Action.CREATE),
			entry("POT", "DELETE", Scope.Resource.POT, null, Scope.Action.DELETE),
			entry("POT.DETAILS", "UPDATE", Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE),
			entry("SHAREHOLDER", "CREATE", Scope.Resource.SHAREHOLDER, null, Scope.Action.CREATE),
			entry("SHAREHOLDER.DETAILS", "UPDATE", Scope.Resource.SHAREHOLDER,
					Scope.SubResource.DETAILS, Scope.Action.UPDATE),
			entry("SHAREHOLDER.WEIGHT", "UPDATE", Scope.Resource.SHAREHOLDER,
					Scope.SubResource.WEIGHT, Scope.Action.UPDATE),
			entry("EXPENSE", "CREATE", Scope.Resource.EXPENSE, null, Scope.Action.CREATE),
			entry("EXPENSE", "DELETE", Scope.Resource.EXPENSE, null, Scope.Action.DELETE),
			entry("EXPENSE.DETAILS", "UPDATE", Scope.Resource.EXPENSE,
					Scope.SubResource.DETAILS, Scope.Action.UPDATE),
			entry("EXPENSE.SHARES", "UPDATE", Scope.Resource.EXPENSE,
					Scope.SubResource.SHARES, Scope.Action.UPDATE));

	private PotAuthorizationSnapshotMapper() {
	}

	static UserContext toUserContext(AuthorizationSnapshot authorization) {
		requireNonNull(authorization, "authorization must not be null");
		Set<Scope> scopes = authorization.permissions().stream()
				.map(SCOPES_BY_PERMISSION::get)
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());
		return new UserContext(UserId.of(authorization.userId().value()), scopes);
	}

	private static Map.Entry<Permission, Scope> entry(
			String objectType,
			String action,
			Scope.Resource resource,
			Scope.SubResource subResource,
			Scope.Action scopeAction) {
		return Map.entry(new Permission(objectType, action), new Scope(resource, subResource, scopeAction));
	}
}
