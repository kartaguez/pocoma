package com.kartaguez.pocoma.infra.persistence.jpa.repository.command;

import java.time.Instant;
import java.util.UUID;

/** Immutable target database representation of one recorded Command. */
public record RecordedCommandRow(
        UUID commandId, String commandType, String payloadJson, Instant submittedAt,
        String authIssuer, String authSubject, UUID bindingId, Instant authValidUntil,
        String authExternalAuthoritiesJson) {
}
