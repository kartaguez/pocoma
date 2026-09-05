package com.kartaguez.pocoma.engine.security;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public record UserContext(UserId userId, Set<Permission> permissions) {

	public UserContext {
		permissions = Set.copyOf(java.util.Objects.requireNonNull(permissions, "permissions must not be null"));
	}
}
