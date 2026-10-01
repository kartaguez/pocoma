package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

/** Execution-only authorization prepared from a locked TARGET_V2 binding occurrence. */
public record ResolvedCommandAuthorization(
		PocomaUserId userId,
		Set<Permission> permissions) implements CommandExecutionAuthorization {

	public ResolvedCommandAuthorization {
		requireNonNull(userId, "userId must not be null");
		permissions = Set.copyOf(requireNonNull(permissions, "permissions must not be null"));
	}
}
