package com.kartaguez.pocoma.runtime.result;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;
import com.kartaguez.pocoma.supra.consume.commandresult.CommandResultConsumptionLocator;
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
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultService;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = PocomaCommandResultConsumptionWorkerApplication.class, properties = {
		"pocoma.command-result-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate" })
class CommandResultRuntimePostgresTest {
	@Autowired
	private ApplicationContext wp2Context;

	@Test
	void publicConsumptionEntrypointsRejectPhysicalAmbientTransaction() {
		var manager = wp2Context.getBean(PlatformTransactionManager.class);
		new TransactionTemplate(manager).execute(status -> {
			var acquisitions = wp2Context.getBeansOfType(AcquireConsumptionUseCase.class);
			Assertions.assertFalse(acquisitions.isEmpty());
			acquisitions.values().forEach(owner -> Assertions.assertThrows(IllegalStateException.class, () -> owner.acquire(null)));
			wp2Context.getBeansOfType(ExecuteConsumptionUseCase.class).values()
				.forEach(owner -> Assertions.assertThrows(IllegalStateException.class, () -> owner.execute(null)));
			wp2Context.getBeansOfType(FinalizeConsumptionUseCase.class).values()
				.forEach(owner -> Assertions.assertThrows(IllegalStateException.class, () -> owner.finalizeConsumption(null)));
			wp2Context.getBeansOfType(HandleConsumptionFailureUseCase.class).values()
				.forEach(owner -> Assertions.assertThrows(IllegalStateException.class, () -> owner.handle(null)));
			wp2Context.getBeansOfType(ConsumptionOrchestrator.class).values()
				.forEach(owner -> Assertions.assertThrows(IllegalStateException.class, () -> owner.run(null)));
			Assertions.assertFalse(status.isRollbackOnly());
			return null;
		});
	}

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
	@Autowired CommandResultConsumptionLocator locator;
	@Autowired AcquireConsumptionUseCase acquire;
	@Autowired ExecuteConsumptionUseCase execute;

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

	@Test void staleResultAndProvenanceRollBackBeforeWinnerPublishes() {
		UUID id = seed("APPLIED");
		var located = locator.openSearch().next().orElseThrow();
		var stale = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(
				located.consumptionKey(), new WorkerId("stale"), new ClaimLease(Duration.ofSeconds(30))))).claim();
		jdbc.update("update consumption_claims set claimed_at=now()-interval '2 minutes', lease_until=now()-interval '1 minute' where claim_id=?", stale.claimId().value());
		var winner = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(
				located.consumptionKey(), new WorkerId("winner"), new ClaimLease(Duration.ofSeconds(30))))).claim();
		assertThrows(LostClaimException.class, () -> execute.execute(new ExecuteConsumptionInput(
				stale.slotId(), stale.claimId(), context -> {
					var result = located.execution().execute(context);
					assertEquals(1L, count("command_results"), "real JDBC write before losing CAS");
					return result;
				})));
		assertEquals(0L, count("command_results"));
		assertEquals(0L, count("consumption_inputs"));
		assertEquals(winner.claimId().value(), jdbc.queryForObject("select current_claim_id from consumption_slots", UUID.class));
		execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
		assertEquals(1L, count("command_results"));
		assertEquals(1L, count("consumption_inputs"));
		assertEquals("COMMAND_TERMINAL_EVENT", jdbc.queryForObject("select subject_type from consumption_inputs", String.class));
		assertEquals("DONE", jdbc.queryForObject("select status from consumption_slots", String.class));
		assertInstanceOf(GetCommandResult.Applied.class, new GetCommandResultService(store).get(new CommandId(id), owner()));
	}

	@Test void identicalReplayIsIdempotentAndDivergentOwnerCannotReplaceResult() {
		UUID id = seed("REJECTED");
		orchestrator.run(input("materialize"));
		var original = store.find(new CommandId(id)).orElseThrow();
		store.ensureResult(original);
		assertThrows(IllegalStateException.class, () -> store.ensureResult(new ImmutableCommandResult(
				new ExternalIdentity("issuer", "other"), original.outcome())));
		assertEquals(original, store.find(new CommandId(id)).orElseThrow());
		assertEquals(1L, count("command_results"));
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
		return new ConsumptionOrchestrationInput(new WorkerId(worker), new ClaimLease(Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 20));
	}
	private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
}
