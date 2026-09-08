package com.kartaguez.pocoma.domain.projection;

import java.time.Instant;
import static java.util.Objects.requireNonNull;

public record ProjectionFailure(ProjectionIdentity identity, Instant failedAt, String terminalFailureCode) {
	public ProjectionFailure {
		requireNonNull(identity); requireNonNull(failedAt); requireNonNull(terminalFailureCode);
		if (terminalFailureCode.isBlank()) throw new IllegalArgumentException("terminalFailureCode must not be blank");
	}
}
