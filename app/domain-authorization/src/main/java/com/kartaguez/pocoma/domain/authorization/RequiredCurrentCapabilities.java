package com.kartaguez.pocoma.domain.authorization;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Complete set of current capabilities required by application orchestration. */
public record RequiredCurrentCapabilities(Set<Permission> permissions) {

	public RequiredCurrentCapabilities {
		permissions = Set.copyOf(requireNonNull(permissions, "permissions must not be null"));
		if (permissions.isEmpty()) {
			throw new IllegalArgumentException("permissions must not be empty");
		}
	}

	public static RequiredCurrentCapabilities of(Permission first, Permission... others) {
		requireNonNull(first, "first must not be null");
		requireNonNull(others, "others must not be null");
		Set<Permission> values = new HashSet<>();
		values.add(first);
		values.addAll(Arrays.asList(others));
		return new RequiredCurrentCapabilities(values);
	}

	public RequiredCurrentCapabilities plus(Permission capability) {
		requireNonNull(capability, "capability must not be null");
		Set<Permission> values = new HashSet<>(permissions);
		values.add(capability);
		return new RequiredCurrentCapabilities(values);
	}

	public boolean contains(Permission capability) {
		return permissions.contains(requireNonNull(capability, "capability must not be null"));
	}
}
