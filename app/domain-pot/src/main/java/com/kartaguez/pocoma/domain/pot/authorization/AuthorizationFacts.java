package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Set;

/** Ephemeral typed business facts resolved for one authorization decision. */
public record AuthorizationFacts(Set<AuthorizationFact> values) {

	public AuthorizationFacts {
		values = Set.copyOf(requireNonNull(values, "values must not be null"));
	}

	public static AuthorizationFacts of(AuthorizationFact... facts) {
		requireNonNull(facts, "facts must not be null");
		return new AuthorizationFacts(Set.copyOf(Arrays.asList(facts)));
	}

	public boolean contains(AuthorizationFact fact) {
		return values.contains(requireNonNull(fact, "fact must not be null"));
	}
}
