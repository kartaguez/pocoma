package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

/** Minimal autonomous Pocoma user. */
public record User(PocomaUserId id) {

	public User {
		requireNonNull(id, "id must not be null");
	}
}
