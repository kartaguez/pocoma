package com.kartaguez.pocoma.infra.persistence.read.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CurrentBindingTerminalCutoverMigrationPostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private static final UUID U1 = UUID.randomUUID(), U2 = UUID.randomUUID();
	private static final UUID B1 = UUID.randomUUID(), B2 = UUID.randomUUID();
	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeEach void prepareV12Cutover() {
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("drop schema if exists pocoma_read cascade");
		jdbc.execute("drop table if exists public.external_identity_binding_facts");
		flyway("12").migrate();
		jdbc.execute("""
				create table public.external_identity_binding_facts (
				 event_id uuid primary key, issuer text not null, subject text not null,
				 binding_revision bigint not null, fact_type text not null,
				 user_id uuid not null, binding_id uuid not null,
				 unique (issuer, subject, binding_revision))
				""");
	}

	@AfterEach void cleanFacts() {
		jdbc.execute("drop table if exists public.external_identity_binding_facts");
	}

	@Test void rejectsAnIdentityMissingFromCurrentBinding() {
		UUID e1 = fact("e1", 0, "ATTACHED", U1, B1);
		fact("e2", 0, "ATTACHED", U2, B2);
		project("e1", 0, "ATTACHED", U1, B1, e1);
		assertCutoverRejected();
	}

	@Test void rejectsAProjectionAtAnEarlierRealRevision() {
		fact("e1", 0, "ATTACHED", U1, B1);
		UUID r1 = fact("e1", 1, "DETACHED", U1, B1);
		fact("e1", 2, "ATTACHED", U2, B2);
		project("e1", 1, "DETACHED", null, null, r1);
		assertCutoverRejected();
	}

	@Test void rejectsAProjectionWithNoTerminalFact() {
		UUID e1 = fact("e1", 0, "ATTACHED", U1, B1);
		project("e1", 0, "ATTACHED", U1, B1, e1);
		project("e2", 0, "ATTACHED", U2, B2, UUID.randomUUID());
		assertCutoverRejected();
	}

	@Test void rejectsDivergentTerminalStateUserAndBindingId() {
		UUID attached = fact("e1", 0, "ATTACHED", U1, B1);
		project("e1", 0, "ATTACHED", U2, B1, attached);
		assertCutoverRejected();
		jdbc.update("update pocoma_read.current_external_identity_binding set user_id=?,binding_id=? where subject='e1'", U1, B2);
		assertCutoverRejected();
		jdbc.execute("update pocoma_read.current_external_identity_binding set user_id=null,binding_id=null,binding_status='DETACHED' where subject='e1'");
		assertCutoverRejected();
	}

	@Test void rejectsNonTerminalSourceEventIdEvenWhenBusinessPayloadMatches() {
		fact("e1", 0, "ATTACHED", U1, B1);
		project("e1", 0, "ATTACHED", U1, B1, UUID.randomUUID());
		assertCutoverRejected();
	}

	@Test void acceptsExactAttachedDetachedReattachedAndR0BaselineWithTerminalProvenance() {
		UUID baseline = fact("baseline", 0, "ATTACHED", U1, B1);
		project("baseline", 0, "ATTACHED", U1, B1, baseline);

		fact("detached", 0, "ATTACHED", U1, B1);
		UUID detached = fact("detached", 1, "DETACHED", U1, B1);
		project("detached", 1, "DETACHED", null, null, detached);

		fact("reattached", 0, "ATTACHED", U1, B1);
		fact("reattached", 1, "DETACHED", U1, B1);
		UUID reattached = fact("reattached", 2, "ATTACHED", U2, B2);
		project("reattached", 2, "ATTACHED", U2, B2, reattached);

		flyway(null).migrate();
		assertEquals(14, jdbc.queryForObject("select max(version::int) from pocoma_read.flyway_schema_history where success", Integer.class));
		assertEquals(3L, jdbc.queryForObject("select count(*) from pocoma_read.current_external_identity_binding", Long.class));
		assertEquals(0L, jdbc.queryForObject("""
				with terminal as (
				 select distinct on (issuer,subject) issuer,subject,event_id
				 from public.external_identity_binding_facts
				 order by issuer,subject,binding_revision desc)
				select count(*) from terminal f full join pocoma_read.current_external_identity_binding p
				 on p.issuer=f.issuer and p.subject=f.subject
				where f.event_id is null or p.source_event_id is distinct from f.event_id
				""", Long.class));
	}

	private void assertCutoverRejected() {
		FlywayException failure = assertThrows(FlywayException.class, () -> flyway(null).migrate());
		assertTrue(failure.toString().contains("terminal binding fact journal"), failure.toString());
		assertEquals(12, jdbc.queryForObject("select max(version::int) from pocoma_read.flyway_schema_history where success", Integer.class));
	}

	private UUID fact(String subject, long revision, String type, UUID user, UUID binding) {
		UUID event = UUID.randomUUID();
		jdbc.update("insert into public.external_identity_binding_facts values (?,?,?,?,?,?,?)",
				event, "issuer", subject, revision, type, user, binding);
		return event;
	}

	private void project(String subject, long revision, String status, UUID user, UUID binding, UUID event) {
		jdbc.update("insert into pocoma_read.current_external_identity_binding values (?,?,?,?,?,?,?,now())",
				"issuer", subject, revision, status, user, binding, event);
	}

	private Flyway flyway(String target) {
		var configuration = Flyway.configure().dataSource(dataSource).defaultSchema("pocoma_read")
				.schemas("pocoma_read").createSchemas(true).locations("classpath:db/read-store/migration");
		if (target != null) configuration.target(target);
		return configuration.load();
	}
}
