package com.kartaguez.pocoma.orchestrator.command.admission;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.authorization.Permission;

/** Translates the stable external Pocoma authority namespace into generic permissions. */
public final class ExternalAuthorityPermissionTranslator {
	private static final String PREFIX = "pocoma:";
	private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9_]*");

	public Set<Permission> translate(Set<String> externalAuthorities) {
		requireNonNull(externalAuthorities, "externalAuthorities must not be null");
		return externalAuthorities.stream()
				.map(ExternalAuthorityPermissionTranslator::translate)
				.flatMap(java.util.Optional::stream)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static java.util.Optional<Permission> translate(String authority) {
		if (authority == null || !authority.startsWith(PREFIX)) return java.util.Optional.empty();
		String[] components = authority.substring(PREFIX.length()).split(":", -1);
		if (components.length != 2
				|| !COMPONENT.matcher(components[0]).matches()
				|| !COMPONENT.matcher(components[1]).matches()) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Permission(
				components[0].toUpperCase(Locale.ROOT), components[1].toUpperCase(Locale.ROOT)));
	}
}
