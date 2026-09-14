package com.kartaguez.pocoma.domain.authorization;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Set;

/** Provider-neutral capabilities presented by the current token. */
public record TokenCapabilities(Set<Permission> permissions) {

	public TokenCapabilities {
		permissions = Set.copyOf(requireNonNull(permissions, "permissions must not be null"));
	}

	public static TokenCapabilities of(Permission... permissions) {
		requireNonNull(permissions, "permissions must not be null");
		return new TokenCapabilities(Set.copyOf(Arrays.asList(permissions)));
	}

	public boolean containsAll(RequiredCurrentCapabilities required) {
		requireNonNull(required, "required must not be null");
		return permissions.containsAll(required.permissions());
	}
}
