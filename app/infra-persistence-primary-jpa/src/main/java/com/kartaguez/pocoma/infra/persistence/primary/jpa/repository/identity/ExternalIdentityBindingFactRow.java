package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity;

import java.time.Instant;
import java.util.UUID;

public record ExternalIdentityBindingFactRow(
		UUID eventId,
		String issuer,
		String subject,
		long bindingRevision,
		String factType,
		UUID userId,
		UUID bindingId,
		Instant recordedAt) {
}
