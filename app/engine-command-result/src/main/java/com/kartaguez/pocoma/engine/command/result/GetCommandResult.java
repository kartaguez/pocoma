package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public sealed interface GetCommandResult {
	record NotReady() implements GetCommandResult {}
	record ProjectionFailed() implements GetCommandResult {}
	record NotFound() implements GetCommandResult {}
	record Applied(UUID potId, long resultingVersion, Instant resolvedAt) implements GetCommandResult {
		public Applied { requireNonNull(potId); requireNonNull(resolvedAt); }
	}
	record Rejected(String code, Instant resolvedAt) implements GetCommandResult {
		public Rejected { requireNonNull(code); requireNonNull(resolvedAt); }
	}
	record Failed(String code, Instant resolvedAt) implements GetCommandResult {
		public Failed { requireNonNull(code); requireNonNull(resolvedAt); }
	}
}
