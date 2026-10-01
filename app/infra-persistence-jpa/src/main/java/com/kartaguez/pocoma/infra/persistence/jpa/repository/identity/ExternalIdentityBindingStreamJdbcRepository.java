package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalIdentityBindingStreamJdbcRepository {
	private static final String CREATE_IF_ABSENT = """
			insert into external_identity_binding_streams (issuer, subject, current_revision)
			values (?, ?, 0)
			on conflict (issuer, subject) do nothing
			""";
	private static final String FIND = """
			select issuer, subject, current_revision
			from external_identity_binding_streams
			where issuer = ? and subject = ?
			""";
	private static final String LOCK = FIND + " for update";
	private static final String ADVANCE = """
			update external_identity_binding_streams
			set current_revision = ?
			where issuer = ? and subject = ? and current_revision = ?
			""";

	private final JdbcTemplate jdbc;

	public ExternalIdentityBindingStreamJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void createIfAbsent(String issuer, String subject) {
		jdbc.update(CREATE_IF_ABSENT, issuer, subject);
	}

	public Optional<ExternalIdentityBindingStreamRow> find(String issuer, String subject) {
		return query(FIND, issuer, subject);
	}

	public ExternalIdentityBindingStreamRow lock(String issuer, String subject) {
		return query(LOCK, issuer, subject).orElseThrow(() ->
				new IllegalStateException("Binding stream disappeared for " + issuer + "/" + subject));
	}

	public void advance(String issuer, String subject, long expectedRevision, long nextRevision) {
		if (jdbc.update(ADVANCE, nextRevision, issuer, subject, expectedRevision) != 1) {
			throw new IllegalStateException("Binding stream revision changed while locked");
		}
	}

	private Optional<ExternalIdentityBindingStreamRow> query(String sql, String issuer, String subject) {
		return jdbc.query(sql, (result, rowNumber) -> new ExternalIdentityBindingStreamRow(
				result.getString("issuer"), result.getString("subject"), result.getLong("current_revision")),
				issuer, subject).stream().findFirst();
	}
}
