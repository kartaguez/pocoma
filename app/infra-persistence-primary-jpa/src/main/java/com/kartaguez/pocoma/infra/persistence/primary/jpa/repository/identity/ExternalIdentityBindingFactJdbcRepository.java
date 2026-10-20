package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.PrimaryWriteParticipation;

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

	public <T> T accept(java.util.function.Supplier<T> action) {
        return PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), action);
    }

    public void append(ExternalIdentityBindingFactRow row) {
        PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), () -> {
		PrimaryWriteParticipation.require(jdbc);
		int rows = jdbc.update(APPEND,
				row.eventId(), row.issuer(), row.subject(), row.bindingRevision(), row.factType(),
				row.userId(), row.bindingId(), Timestamp.from(row.recordedAt()), row.issuer(), row.subject());
        if (rows != 1) throw new IllegalStateException("Binding fact must insert exactly one row");

        return null;

        });
    }

	public Optional<ExternalIdentityBindingFactRow> findByEventId(UUID eventId) {
		return jdbc.query(FIND, (rs, row) -> new ExternalIdentityBindingFactRow(
				rs.getObject("event_id", UUID.class), rs.getString("issuer"), rs.getString("subject"),
				rs.getLong("binding_revision"), rs.getString("fact_type"),
				rs.getObject("user_id", UUID.class), rs.getObject("binding_id", UUID.class),
				rs.getTimestamp("recorded_at").toInstant()), eventId).stream().findFirst();
	}
}
