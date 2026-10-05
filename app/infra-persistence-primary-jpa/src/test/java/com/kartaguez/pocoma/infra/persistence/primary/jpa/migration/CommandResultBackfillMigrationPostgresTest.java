package com.kartaguez.pocoma.infra.persistence.primary.jpa.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The old READ artifact/failure is historical; the terminal authority survives cutover. */
@Testcontainers
class CommandResultBackfillMigrationPostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private JdbcTemplate jdbc;
	private final Instant now = Instant.parse("2026-10-03T10:00:00Z");

	@BeforeEach void oldDatabase() {
		jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword()));
		jdbc.execute("drop schema if exists pocoma_read cascade");
		Flyway old = flyway("24");
		old.clean(); old.migrate();
		jdbc.execute("create schema pocoma_read");
		jdbc.execute("""
			create table pocoma_read.projection_root (
			 id bigint generated always as identity primary key, projection_type text,
			 target_object_type text, target_object_id text, target_version bigint)
			""");
		jdbc.execute("""
			create table pocoma_read.projection_artifact (
			 projection_root_id bigint, artifact_type text, artifact_key text, payload jsonb)
			""");
		jdbc.execute("""
			create table pocoma_read.projection_failure (
			 failure_id uuid, projection_type text, target_object_id text)
			""");
	}

	@Test void backfillsOldArtifactAndTechnicalFailureFromAuthoritativeEvents() {
		UUID applied = seed("APPLIED"), failed = seed("FAILED");
		legacyArtifact(applied, "subject");
		jdbc.update("""
			insert into projection_tasks(id,projection_type,target_object_type,target_object_id,
			 target_version,partition_hash,created_at)
			values (?,'COMMAND_RESULT','COMMAND',?,1,0,?)
			""", UUID.randomUUID(), applied.toString(), Timestamp.from(now));
		jdbc.update("insert into pocoma_read.projection_failure values (?,'COMMAND_RESULT',?)",
				UUID.randomUUID(), failed.toString());
		flyway(null).migrate();
		assertEquals(2L, jdbc.queryForObject("select count(*) from command_results", Long.class));
		assertEquals("subject", jdbc.queryForObject(
				"select owner_subject from command_results where command_id=?", String.class, applied));
		assertEquals("FAILED", jdbc.queryForObject(
				"select outcome_type from command_results where command_id=?", String.class, failed));
		assertEquals(0L, jdbc.queryForObject("select count(*) from pocoma_read.projection_artifact", Long.class));
		assertEquals(0L, jdbc.queryForObject("select count(*) from pocoma_read.projection_failure", Long.class));
		assertEquals(0L, jdbc.queryForObject(
				"select count(*) from projection_tasks where projection_type='COMMAND_RESULT'", Long.class));
	}

	@Test void contradictoryLegacyArtifactAbortsCutoverWithoutPartialResults() {
		UUID commandId = seed("APPLIED");
		legacyArtifact(commandId, "wrong-subject");
		assertThrows(FlywayException.class, () -> flyway(null).migrate());
		assertEquals(0L, jdbc.queryForObject("select count(*) from information_schema.tables "
				+ "where table_schema='public' and table_name='command_results'", Long.class));
	}

	private UUID seed(String type) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into recorded_commands(command_id,command_type,payload_json,submitted_at,
			 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
			values (?,'TEST','{}',?,'issuer','subject',?,?, '[]'::jsonb)
			""", id, Timestamp.from(now), UUID.randomUUID(), Timestamp.from(now.plusSeconds(60)));
		jdbc.update("""
			insert into command_outcomes(command_id,outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,?,?,?,?,?)
			""", id, type, type.equals("APPLIED") ? UUID.fromString("10000000-0000-4000-8000-000000000001") : null,
				type.equals("APPLIED") ? 7L : null,
				type.equals("FAILED") ? "COMMAND_PROCESSING_FAILED" : null, Timestamp.from(now));
		jdbc.update("""
			insert into command_terminal_events(event_id,event_type,command_id,command_partition_hash,trace_id,recorded_at)
			values (?, ?, ?, 0, null, ?)
			""", UUID.randomUUID(), "COMMAND_" + type, id, Timestamp.from(now));
		return id;
	}

	private void legacyArtifact(UUID commandId, String subject) {
		jdbc.update("insert into pocoma_read.projection_root(projection_type,target_object_type,target_object_id,target_version) "
				+ "values ('COMMAND_RESULT','COMMAND',?,1)", commandId.toString());
		String payload = """
			{"commandId":"%s","outcome":"APPLIED","potId":"10000000-0000-4000-8000-000000000001",
			 "resultingVersion":7,"code":null,"resolvedAt":"2026-10-03T10:00:00Z",
			 "visibility":"EXACT_EXTERNAL_IDENTITY","visibleToExternalIdentity":{"issuer":"issuer","subject":"%s"}}
			""".formatted(commandId, subject);
		jdbc.update("""
			insert into pocoma_read.projection_artifact(projection_root_id,artifact_type,artifact_key,payload)
			select id,'COMMAND_RESULT',?,?::jsonb from pocoma_read.projection_root where target_object_id=?
			""", commandId.toString(), payload, commandId.toString());
	}

	private Flyway flyway(String version) {
		var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false);
		if (version != null) config.target(MigrationVersion.fromVersion(version));
		return config.load();
	}
}
