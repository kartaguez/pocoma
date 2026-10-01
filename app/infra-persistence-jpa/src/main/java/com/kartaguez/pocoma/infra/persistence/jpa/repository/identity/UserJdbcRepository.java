package com.kartaguez.pocoma.infra.persistence.jpa.repository.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserJdbcRepository {
	private final JdbcTemplate jdbc;

	public UserJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID userId) {
		jdbc.update("insert into users (user_id) values (?)", userId);
	}

	public Optional<UUID> findById(UUID userId) {
		return jdbc.query("select user_id from users where user_id = ?",
				(result, rowNumber) -> result.getObject("user_id", UUID.class), userId)
				.stream().findFirst();
	}
}
