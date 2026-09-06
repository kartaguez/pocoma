package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.authorization.PocomaPermissions;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.pot.command.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionKeys;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
		"pocoma.command-consumption.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommandConsumptionMultiWorkerPostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired RecordedCommandPort commands;
	@Autowired TransactionRunner transactions;
	@Autowired ConsumptionOrchestrator orchestrator;
	@Autowired JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired JdbcTemplate jdbc;
	@Autowired Clock clock;

	@Test
	void twoWorkersDrainTheSameBacklogWithoutDuplicateEffects() throws Exception {
		String prefix = "multi-worker-" + UUID.randomUUID();
		List<RecordedCommand> backlog = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			UUID userId = UUID.randomUUID();
			String payload = "{\"label\":\"%s-%d\",\"creatorId\":\"%s\"}"
					.formatted(prefix, index, userId);
			backlog.add(command(payload, userId));
		}
		transactions.runInTransaction(() -> backlog.forEach(commands::insert));

		ConsumptionPollingWorker first = worker("command-runtime-a");
		ConsumptionPollingWorker second = worker("command-runtime-b");
		try {
			first.start();
			second.start();
			await(() -> backlog.stream().allMatch(command -> lifecycle
					.findSlot(CommandConsumptionKeys.forCommand(command.commandId()))
					.flatMap(slot -> slot.terminalOutcome()).filter(TerminalOutcome.SUCCESS::equals).isPresent()));
		}
		finally {
			CountDownLatch stopped = new CountDownLatch(2);
			first.requestStop(stopped::countDown);
			second.requestStop(stopped::countDown);
			assertTrue(stopped.await(2, TimeUnit.SECONDS), "workers did not stop cooperatively");
		}

		assertEquals(4, jdbc.queryForObject(
				"select count(*) from pot_headers where label like ?", Integer.class, prefix + "%"));
		assertEquals(4, jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class));
		for (RecordedCommand command : backlog) {
			var slot = lifecycle.findSlot(CommandConsumptionKeys.forCommand(command.commandId())).orElseThrow();
			assertEquals(1, lifecycle.findClaims(slot.slotId()).size());
		}
	}

	private ConsumptionPollingWorker worker(String workerId) {
		return new ConsumptionPollingWorker(orchestrator,
				new ConsumptionWorkerSettings(true, new WorkerId(workerId), new ClaimLease(Duration.ofSeconds(30)),
						new ConsumptionOrchestrationBudget(100, 10), Duration.ofMillis(20), Duration.ofMillis(20)),
				clock, new ConditionConsumptionWaiter());
	}

	private static RecordedCommand command(String payload, UUID userId) {
		Instant now = Instant.now();
		return new RecordedCommand(new CommandId(UUID.randomUUID()), PotCommandTypes.POT_CREATE_V1, payload, now,
				new AuthorizationSnapshot(new PocomaUserId(userId), Set.of(PocomaPermissions.POT_CREATE),
						now.minusSeconds(10), now.minusSeconds(10), now.plusSeconds(60), "runtime-test"));
	}

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("test interrupted", exception);
			}
		}
		assertTrue(condition.getAsBoolean(), "backlog was not consumed before timeout");
	}
}
