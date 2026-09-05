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

	private static final Map<Permission, Set<Scope>> SCOPES_BY_PERMISSION = Map.ofEntries(
			entry("POT", "VIEW"),
			entry("POT", "CREATE", scope(Scope.Resource.POT, null, Scope.Action.CREATE)),
			entry("POT", "UPDATE", scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE)),
			entry("POT", "DELETE", scope(Scope.Resource.POT, null, Scope.Action.DELETE)),
			entry("POT", "VIEW_ARCHIVE"),
			entry("SHAREHOLDER", "VIEW"),
			entry("SHAREHOLDER", "CREATE", scope(Scope.Resource.SHAREHOLDER, null, Scope.Action.CREATE)),
			entry("SHAREHOLDER", "UPDATE",
					scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.DETAILS, Scope.Action.UPDATE),
					scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.WEIGHT, Scope.Action.UPDATE)),
			entry("SHAREHOLDER", "DELETE"),
			entry("SHAREHOLDER", "VIEW_ARCHIVE"),
			entry("EXPENSE", "VIEW"),
			entry("EXPENSE", "CREATE", scope(Scope.Resource.EXPENSE, null, Scope.Action.CREATE)),
			entry("EXPENSE", "UPDATE",
					scope(Scope.Resource.EXPENSE, Scope.SubResource.DETAILS, Scope.Action.UPDATE),
					scope(Scope.Resource.EXPENSE, Scope.SubResource.SHARES, Scope.Action.UPDATE)),
			entry("EXPENSE", "DELETE", scope(Scope.Resource.EXPENSE, null, Scope.Action.DELETE)),
			entry("EXPENSE", "VIEW_ARCHIVE"),
			entry("BALANCE", "VIEW"));

	private PotAuthorizationSnapshotMapper() {
	}

	static UserContext toUserContext(AuthorizationSnapshot authorization) {
		requireNonNull(authorization, "authorization must not be null");
		Set<Scope> scopes = authorization.permissions().stream()
				.flatMap(permission -> scopesFor(permission).stream())
				.collect(Collectors.toUnmodifiableSet());
		return new UserContext(UserId.of(authorization.userId().value()), scopes);
	}

	private static Set<Scope> scopesFor(Permission permission) {
		Set<Scope> scopes = SCOPES_BY_PERMISSION.get(permission);
		if (scopes == null) {
			throw new IllegalArgumentException(
					"Unsupported Pot permission: " + permission.objectType() + " / " + permission.action());
		}
		return scopes;
	}

	private static Map.Entry<Permission, Set<Scope>> entry(String objectType, String action, Scope... scopes) {
		return Map.entry(new Permission(objectType, action), Set.of(scopes));
	}

	private static Scope scope(
			Scope.Resource resource,
			Scope.SubResource subResource,
			Scope.Action action) {
		return new Scope(resource, subResource, action);
	}
}
