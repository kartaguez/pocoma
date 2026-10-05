package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Permanent reservation of every BindingId, including detached occurrences. */
public final class ExternalIdentityBindingOccurrenceJdbcRepository {
	private final JdbcTemplate jdbc;

	public ExternalIdentityBindingOccurrenceJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean reserve(UUID bindingId, String issuer, String subject, UUID userId, long revision) {
		return jdbc.update("""
				insert into external_identity_binding_occurrences
				    (binding_id, issuer, subject, user_id, attached_revision, created_at)
				values (?, ?, ?, ?, ?, now()) on conflict (binding_id) do nothing
				""", bindingId, issuer, subject, userId, revision) == 1;
	}
}
