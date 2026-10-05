package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

/** Internal WRITE observation; the revision is never part of a Command. */
public record ObservedBinding(PocomaUserId userId, BindingRevision revision) {
	public ObservedBinding {
		requireNonNull(userId, "userId must not be null");
		requireNonNull(revision, "revision must not be null");
	}
}
