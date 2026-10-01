package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalIdentityBindingFactJdbcRepository {
	private static final String APPEND = """
			insert into external_identity_binding_facts
			(event_id, issuer, subject, binding_revision, fact_type, user_id, binding_id, recorded_at,
			 partition_hash)
			values (?, ?, ?, ?, ?, ?, ?, ?,
			        hashtext(jsonb_build_array(?, ?)::text))
			""";

	private final JdbcTemplate jdbc;

	public ExternalIdentityBindingFactJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void append(ExternalIdentityBindingFactRow row) {
		jdbc.update(APPEND,
				row.eventId(), row.issuer(), row.subject(), row.bindingRevision(), row.factType(),
				row.userId(), row.bindingId(), Timestamp.from(row.recordedAt()), row.issuer(), row.subject());
	}
}
