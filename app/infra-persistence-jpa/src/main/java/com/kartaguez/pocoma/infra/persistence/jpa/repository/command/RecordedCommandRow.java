package com.kartaguez.pocoma.infra.persistence.jpa.repository.command;

import java.time.Instant;
import java.util.UUID;

/** Immutable database representation of one recorded Command. */
public record RecordedCommandRow(
		UUID commandId,
		String commandType,
		String payloadJson,
		Instant submittedAt,
		UUID authUserId,
		String authIssuer,
		Instant authAuthenticatedAt,
		Instant authIssuedAt,
		Instant authValidUntil,
		String authPermissionsJson) {
}
