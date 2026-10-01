package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExternalIdentityBindingFactJdbcRepository {
	private static final String APPEND = """
			insert into external_identity_binding_facts
			(event_id, issuer, subject, binding_revision, fact_type, user_id, binding_id, recorded_at,
			 partition_hash)
			values (?, ?, ?, ?, ?, ?, ?, ?,
			        hashtext(jsonb_build_array(?, ?)::text))
			""";
	private static final String FIND = """
			select event_id, issuer, subject, binding_revision, fact_type, user_id, binding_id, recorded_at
			from external_identity_binding_facts where event_id = ?
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

	public Optional<ExternalIdentityBindingFactRow> findByEventId(UUID eventId) {
		return jdbc.query(FIND, (rs, row) -> new ExternalIdentityBindingFactRow(
				rs.getObject("event_id", UUID.class), rs.getString("issuer"), rs.getString("subject"),
				rs.getLong("binding_revision"), rs.getString("fact_type"),
				rs.getObject("user_id", UUID.class), rs.getObject("binding_id", UUID.class),
				rs.getTimestamp("recorded_at").toInstant()), eventId).stream().findFirst();
	}
}
