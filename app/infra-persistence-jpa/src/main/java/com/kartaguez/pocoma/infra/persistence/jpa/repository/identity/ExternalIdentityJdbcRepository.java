package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalIdentityJdbcRepository {
	private static final String SELECT_USER_ID = """
			select pocoma_user_id
			from external_identities
			where issuer = ? and subject = ?
			""";

	private final JdbcTemplate jdbc;

	public ExternalIdentityJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UUID> findUserId(String issuer, String subject) {
		return jdbc.query(SELECT_USER_ID,
				(result, rowNumber) -> result.getObject("pocoma_user_id", UUID.class), issuer, subject)
				.stream().findFirst();
	}
}
