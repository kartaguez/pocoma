package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalIdentityJdbcRepository {
	private static final String SELECT_USER_ID = """
			select user_id
			from external_identities
			where issuer = ? and subject = ?
			""";
	private static final String SELECT_EXACT_USER_ID = """
			select user_id
			from external_identities
			where issuer = ? and subject = ? and binding_id = ?
			""";
	private static final String ACQUIRE = """
			insert into external_identities (issuer, subject, user_id, binding_id)
			values (?, ?, ?, ?)
			on conflict (issuer, subject) do nothing
			""";
	private static final String DETACH = """
			delete from external_identities
			where issuer = ? and subject = ? and binding_id = ?
			""";

	private final JdbcTemplate jdbc;

	public ExternalIdentityJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UUID> findUserId(String issuer, String subject) {
		return jdbc.query(SELECT_USER_ID,
				(result, rowNumber) -> result.getObject("user_id", UUID.class), issuer, subject)
				.stream().findFirst();
	}

	public boolean hasActiveBinding(String issuer, String subject) {
		return findUserId(issuer, subject).isPresent();
	}

	public Optional<UUID> findUserId(String issuer, String subject, UUID bindingId) {
		return queryUserId(SELECT_EXACT_USER_ID, issuer, subject, bindingId);
	}

	public boolean acquire(String issuer, String subject, UUID userId, UUID bindingId) {
		return jdbc.update(ACQUIRE, issuer, subject, userId, bindingId) == 1;
	}

	public boolean detach(String issuer, String subject, UUID bindingId) {
		return jdbc.update(DETACH, issuer, subject, bindingId) == 1;
	}

	private Optional<UUID> queryUserId(String sql, String issuer, String subject, UUID bindingId) {
		return jdbc.query(sql, (result, rowNumber) -> result.getObject("user_id", UUID.class),
				issuer, subject, bindingId).stream().findFirst();
	}
}
