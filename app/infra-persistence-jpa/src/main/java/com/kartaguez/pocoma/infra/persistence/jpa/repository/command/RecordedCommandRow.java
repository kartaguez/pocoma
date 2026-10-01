package com.kartaguez.pocoma.infra.persistence.jpa.repository.command;

import java.time.Instant;
import java.util.UUID;

/** Immutable database representation of one recorded Command. */
public record RecordedCommandRow(
		UUID commandId,
		String commandType,
		String payloadJson,
		Instant submittedAt,
		int envelopeVersion,
		UUID authUserId,
		String authIssuer,
		String authSubject,
		UUID bindingId,
		Instant authAuthenticatedAt,
		Instant authIssuedAt,
		Instant authValidUntil,
		String authPermissionsJson,
		String authExternalAuthoritiesJson) {
}
