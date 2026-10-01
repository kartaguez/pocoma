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

	private final JdbcTemplate jdbc;

	public ExternalIdentityBindingStreamJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void createIfAbsent(String issuer, String subject) {
		jdbc.update(CREATE_IF_ABSENT, issuer, subject);
	}

	public Optional<ExternalIdentityBindingStreamRow> find(String issuer, String subject) {
		return jdbc.query(FIND, (result, rowNumber) -> new ExternalIdentityBindingStreamRow(
				result.getString("issuer"), result.getString("subject"), result.getLong("current_revision")),
				issuer, subject).stream().findFirst();
	}
}
