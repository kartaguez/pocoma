package com.kartaguez.pocoma.runtime.result;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.PocomaCommandResultConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.result.GetCommandResult;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultService;
import com.kartaguez.pocoma.engine.command.result.CommandResultStore;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;

@SpringBootTest(classes = PocomaCommandResultConsumptionWorkerApplication.class, properties = {
		"pocoma.command-result-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate" })
class CommandResultRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }
	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired ConsumptionOrchestrator orchestrator;
	@Autowired CommandResultStore store;

	@BeforeEach void clean() {
		jdbc.execute("truncate table command_results, command_terminal_events, command_outcomes, recorded_commands cascade");
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims cascade");
	}

	@Test void terminalEventsMaterializeAllOutcomesUnderDistinctDoneSlotsAndExactOwner() {
		UUID applied = seed("APPLIED"), rejected = seed("REJECTED"), failed = seed("FAILED");
		orchestrator.run(input("first"));
		assertEquals(3L, count("command_results"));
		assertEquals(3L, jdbc.queryForObject("select count(*) from consumption_slots where status='DONE' and consumer_type='COMMAND_RESULT_MATERIALIZER_V2'", Long.class));
		GetCommandResultService get = new GetCommandResultService(store);
		assertInstanceOf(GetCommandResult.Applied.class, get.get(new CommandId(applied), owner()));
		assertInstanceOf(GetCommandResult.Rejected.class, get.get(new CommandId(rejected), owner()));
		assertInstanceOf(GetCommandResult.Failed.class, get.get(new CommandId(failed), owner()));
		assertInstanceOf(GetCommandResult.NotFound.class, get.get(new CommandId(applied),
				new ExternalIdentity("issuer", "other")));
		orchestrator.run(input("replay"));
		assertEquals(3L, count("command_results"));
		assertEquals(0L, jdbc.queryForObject("select count(*) from projection_tasks where projection_type='COMMAND_RESULT'", Long.class));
	}

	@Test void retryAfterWriterFailureRollsBackAndConverges() {
		UUID id = seed("APPLIED");
		jdbc.execute("create function reject_result() returns trigger language plpgsql as $$ begin raise exception 'temporary'; end $$");
		jdbc.execute("create trigger reject_result_insert before insert on command_results for each row execute function reject_result()");
		try {
			orchestrator.run(input("failure"));
			assertEquals(0L, count("command_results"));
			assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots where consumer_type='COMMAND_RESULT_MATERIALIZER_V2'", String.class));
		} finally {
			jdbc.execute("drop trigger reject_result_insert on command_results");
			jdbc.execute("drop function reject_result()");
		}
		jdbc.update("update consumption_slots set next_claim_at=now()");
		orchestrator.run(input("retry"));
		assertEquals(1L, count("command_results"));
		assertInstanceOf(GetCommandResult.Applied.class,
				new GetCommandResultService(store).get(new CommandId(id), owner()));
	}

	private UUID seed(String type) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into recorded_commands(command_id,command_type,payload_json,submitted_at,
			 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
			values (?,'TEST','{}',?,'issuer','subject',?,?, '[]'::jsonb)
			""", id, Timestamp.from(Instant.now()), UUID.randomUUID(), Timestamp.from(Instant.now().plusSeconds(60)));
		jdbc.update("""
			insert into command_outcomes(command_id,outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,?,?,?,?,?)
			""", id, type, type.equals("APPLIED") ? UUID.randomUUID() : null,
				type.equals("APPLIED") ? 7L : null,
				type.equals("REJECTED") ? "POT_VERSION_CONFLICT" : type.equals("FAILED") ? "COMMAND_PROCESSING_FAILED" : null,
				Timestamp.from(Instant.now()));
		jdbc.update("""
			insert into command_terminal_events(event_id,event_type,command_id,command_partition_hash,trace_id,recorded_at)
			values (?, ?, ?, 0, null, ?)
			""", UUID.randomUUID(), "COMMAND_" + type, id, Timestamp.from(Instant.now()));
		return id;
	}

	private static ExternalIdentity owner() { return new ExternalIdentity("issuer", "subject"); }
	private static ConsumptionOrchestrationInput input(String worker) {
		return new ConsumptionOrchestrationInput(new WorkerId(worker), new ClaimLease(java.time.Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 20));
	}
	private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
}
