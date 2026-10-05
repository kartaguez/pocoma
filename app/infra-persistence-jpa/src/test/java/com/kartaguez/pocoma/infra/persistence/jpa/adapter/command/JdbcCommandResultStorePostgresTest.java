package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.PublishedCommandResult;

@Testcontainers
class JdbcCommandResultStorePostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private static JdbcTemplate jdbc;
	private static JdbcCommandResultStore store;
	private static final ExternalIdentity OWNER = new ExternalIdentity("issuer", "subject");
	private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

	@BeforeAll static void migrate() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword()));
		store = new JdbcCommandResultStore(jdbc);
	}

	@BeforeEach void clean() {
		jdbc.execute("truncate table command_results, command_terminal_events, command_outcomes, recorded_commands cascade");
	}

	@Test void insertIdenticalReplayDivergenceAndImmutableOwner() {
		CommandId id = recorded();
		ImmutableCommandResult first = new ImmutableCommandResult(OWNER,
				new PublishedCommandResult.Applied(id, UUID.randomUUID(), 7, NOW));
		store.ensureResult(first);
		store.ensureResult(first);
		assertEquals(first, store.find(id).orElseThrow());
		assertEquals(1L, jdbc.queryForObject("select count(*) from command_results", Long.class));
		assertThrows(IllegalStateException.class, () -> store.ensureResult(new ImmutableCommandResult(OWNER,
				new PublishedCommandResult.Applied(id, UUID.randomUUID(), 7, NOW))));
		assertThrows(IllegalStateException.class, () -> store.ensureResult(new ImmutableCommandResult(
				new ExternalIdentity("issuer", "different"), first.outcome())));
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"update command_results set owner_subject='changed' where command_id=?", id.value()));
		assertEquals(first, store.find(id).orElseThrow());
	}

	@Test void sqlRejectsInvalidShapeAndFailedCode() {
		CommandId id = recorded();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
			insert into command_results(command_id,owner_issuer,owner_subject,schema_version,
			 outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,'issuer','subject',1,'APPLIED',null,1,null,?)
			""", id.value(), Timestamp.from(NOW)));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
			insert into command_results(command_id,owner_issuer,owner_subject,schema_version,
			 outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,'issuer','subject',2,'REJECTED',null,null,'DENIED',?)
			""", id.value(), Timestamp.from(NOW)));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
			insert into command_results(command_id,owner_issuer,owner_subject,schema_version,
			 outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,'issuer','subject',1,'FAILED',null,null,'TECHNICAL_ERROR',?)
			""", id.value(), Timestamp.from(NOW)));
		assertTrue(store.find(id).isEmpty());
	}

	private CommandId recorded() {
		CommandId id = new CommandId(UUID.randomUUID());
		jdbc.update("""
			insert into recorded_commands(command_id,command_type,payload_json,submitted_at,
			 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
			values (?,'TEST','{}',?,'issuer','subject',?,?, '[]'::jsonb)
			""", id.value(), Timestamp.from(NOW), UUID.randomUUID(), Timestamp.from(NOW.plusSeconds(60)));
		return id;
	}
}
