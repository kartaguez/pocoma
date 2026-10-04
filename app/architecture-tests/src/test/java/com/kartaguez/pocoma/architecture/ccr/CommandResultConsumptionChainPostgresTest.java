package com.kartaguez.pocoma.architecture.ccr;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.engine.materialize.commandresult.MaterializeCommandResultService;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JdbcCommandResultSource;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JdbcCommandResultStore;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.supra.consume.commandresult.CommandResultConsumptionLocator;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;

/** CCR: Result publication shares the existing Consumption execution transaction and final fence. */
@SpringBootTest(classes = CommandResultConsumptionChainPostgresTest.TestApplication.class, properties = {
		"pocoma.command-result-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate" })
class CommandResultConsumptionChainPostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }
	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired AcquireConsumptionUseCase acquire;
	@Autowired ExecuteConsumptionUseCase execute;
	@Autowired JdbcCommandResultSource source;
	@Autowired MaterializeCommandResultService materializer;
	@Autowired ConsumptionOrchestrator orchestrator;

	@BeforeEach void clean() {
		jdbc.execute("truncate table command_results, command_terminal_events, command_outcomes, recorded_commands cascade");
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims cascade");
	}

	@Test void validClaimPublishesResultAndFinalizesExactlyOnce() {
		Seed seed = seed();
		var claim = claim(seed.eventId());
		execute.execute(execution(claim, seed, false));
		assertEquals(1L, count("command_results"));
		assertEquals("DONE", jdbc.queryForObject("select status from consumption_slots", String.class));
		assertEquals(1L, count("consumption_inputs"));
		assertInstanceOf(AcquireResult.AlreadyDone.class, acquire.acquire(new AcquireConsumptionInput(
				CommandResultConsumptionLocator.key(seed.eventId()), new WorkerId("replay"), lease())));
	}

	@Test void lostClaimBeforeCommitPublishesNoResult() {
		Seed seed = seed();
		var claim = claim(seed.eventId());
		jdbc.update("update consumption_slots set current_claim_id=null where slot_id=?",
				claim.claim().slotId());
		assertThrows(LostClaimException.class, () -> execute.execute(execution(claim, seed, false)));
		assertEquals(0L, count("command_results"));
		assertEquals(0L, count("consumption_inputs"));
		assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots", String.class));
	}

	@Test void exceptionAfterInsertRollsBackResultAndSlotFinalization() {
		Seed seed = seed();
		var claim = claim(seed.eventId());
		assertThrows(IllegalStateException.class, () -> execute.execute(execution(claim, seed, true)));
		assertEquals(0L, count("command_results"));
		assertEquals(0L, count("consumption_inputs"));
		assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots", String.class));
	}

	@Test void takeoverFencesStaleWorkerAndConvergesToOneResult() {
		Seed seed = seed();
		var stale = claim(seed.eventId());
		jdbc.update("update consumption_claims set lease_until=now() where claim_id=?",
				stale.claim().claimId().value());
		orchestrator.run(input("takeover"));
		assertEquals(1L, count("command_results"));
		assertEquals("DONE", jdbc.queryForObject("select status from consumption_slots", String.class));
		assertThrows(DataIntegrityViolationException.class,
				() -> execute.execute(execution(stale, seed, false)));
		assertEquals(1L, count("command_results"));
	}

	@Test void twoWorkersRaceWithoutDivergentPublication() throws Exception {
		seed();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> orchestrator.run(input("worker-a")));
			var second = pool.submit(() -> orchestrator.run(input("worker-b")));
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		assertEquals(1L, count("command_results"));
		assertEquals(1L, count("consumption_slots"));
		assertEquals("DONE", jdbc.queryForObject("select status from consumption_slots", String.class));
	}

	private AcquireResult.Acquired claim(UUID eventId) {
		return assertInstanceOf(AcquireResult.Acquired.class, acquire.acquire(new AcquireConsumptionInput(
				CommandResultConsumptionLocator.key(eventId), new WorkerId("worker"), lease())));
	}

	private ExecuteConsumptionInput execution(AcquireResult.Acquired acquired, Seed seed, boolean failAfterInsert) {
		return new ExecuteConsumptionInput(acquired.claim().slotId(), acquired.claim().claimId(), context -> {
			materializer.materialize(seed.commandId(), source.reload(seed.eventId()));
			if (failAfterInsert) throw new IllegalStateException("crash after insert");
			return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),
					List.of(new ConsumptionInput(context.slotId(), "COMMAND_TERMINAL_EVENT",
							seed.eventId().toString(), 1)), List.of());
		});
	}

	private Seed seed() {
		UUID commandId = UUID.randomUUID(), eventId = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("""
			insert into recorded_commands(command_id,command_type,payload_json,submitted_at,
			 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
			values (?,'TEST','{}',?,'issuer','subject',?,?, '[]'::jsonb)
			""", commandId, now, UUID.randomUUID(), Timestamp.from(Instant.now().plusSeconds(60)));
		jdbc.update("""
			insert into command_outcomes(command_id,outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,'APPLIED',?,7,null,?)
			""", commandId, UUID.randomUUID(), now);
		jdbc.update("""
			insert into command_terminal_events(event_id,event_type,command_id,command_partition_hash,trace_id,recorded_at)
			values (?,'COMMAND_APPLIED',?,0,null,?)
			""", eventId, commandId, now);
		return new Seed(commandId, eventId);
	}

	private static ClaimLease lease() { return new ClaimLease(Duration.ofSeconds(30)); }
	private static ConsumptionOrchestrationInput input(String worker) {
		return new ConsumptionOrchestrationInput(new WorkerId(worker), lease(),
				new ConsumptionOrchestrationBudget(10, 10));
	}
	private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
	private record Seed(UUID commandId, UUID eventId) {}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({CommandResultRuntimeConfiguration.class, JdbcCommandResultSource.class,
			JdbcCommandResultStore.class})
	static class TestApplication {}
}
