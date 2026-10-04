package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionFailureId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.pot.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.read.projection.port.ProjectionReadResult;
import com.kartaguez.pocoma.port.projection.ProjectionReadPort;
import com.kartaguez.pocoma.port.projection.ProjectionWritePort;
import com.kartaguez.pocoma.domain.projection.pot.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskKeys;
import com.kartaguez.pocoma.port.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskExecutionResult;
import com.kartaguez.pocoma.engine.consume.projectiontask.TerminalProjectionPreparationException;
import com.kartaguez.pocoma.port.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ExecuteProjectionTaskUseCase;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionEngineService;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionProducerDeclaration;
import com.kartaguez.pocoma.engine.read.pot.PotReads;
import com.kartaguez.pocoma.engine.read.pot.ReadPotResult;
import com.kartaguez.pocoma.engine.read.projection.service.ExactProjectionReads;
import com.kartaguez.pocoma.engine.consume.projectiontask.historical.HistoricalPotReconstructionException;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcProjectionTaskStoreAdapter;
import com.kartaguez.pocoma.infra.persistence.projection.jdbc.JdbcProjectionStoreAdapter;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

@SpringBootTest(properties = {
		"pocoma.projection-task-consumption.enabled=true",
		"pocoma.projection-task-consumption.catalog-projection-types=AUTH,POT_BALANCES,READ_POT",
		"pocoma.projection-task-consumption.locator-projection-types=AUTH,POT_BALANCES,READ_POT",
		"pocoma.projection-task-consumption.poll-interval=1h",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class CanonicalProjectionTaskRuntimePostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private ApplicationContext context;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private FinalizeConsumptionUseCase finalizer;
	@Autowired private HandleConsumptionFailureUseCase retryHandler;
	@Autowired private ProjectionWritePort writer;
	@Autowired private ProjectionValidator validator;
	@Autowired private ProjectionTaskStorePort tasks;
	@Autowired private ProjectionReadPort reader;
	@Autowired private ConsumptionPollingWorker worker;

	@BeforeEach
	void clean() throws InterruptedException {
		var stopped = new CountDownLatch(1);
		worker.requestStop(stopped::countDown);
		if (!stopped.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("projection worker did not stop");
		}
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "projection_tasks cascade");
		jdbc.execute("truncate table pocoma_read.projection_failure, pocoma_read.projection_artifact, "
				+ "pocoma_read.projection_root cascade");
		jdbc.execute("truncate table expense_shares, expense_headers, shareholders, pot_headers, "
				+ "pot_version_metadata, pot_global_versions cascade");
	}

	@Test
	void activePollingAuthorityUsesOnlyTheCanonicalProjectionTaskGraph() {
		assertEquals(1, context.getBeansOfType(CanonicalProjectionTaskRuntimeConfiguration.class).size());
		assertFalse(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType)
				.filter(java.util.Objects::nonNull).map(Class::getName)
				.anyMatch(name -> name.contains(".taskexecution.")
						|| name.contains(".locator.consumption.task.")
						|| name.endsWith("TaskConsumptionRuntimeConfiguration")));
		assertEquals(1, context.getBeansOfType(ConsumptionPollingWorker.class).size());
		assertEquals(1, context.getBeansOfType(ProjectionTaskWorkerLifecycle.class).size());

		ConsumptionOrchestrator activeOrchestrator = context.getBean(ConsumptionOrchestrator.class);
		assertInstanceOf(ProjectionTaskConsumptionOrchestrator.class, activeOrchestrator);
		assertInstanceOf(ProjectionEngineService.class, context.getBean(ExecuteProjectionTaskUseCase.class));
		assertEquals(Set.of(AuthProjectionDefinition.PROJECTION_TYPE, ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE),
				context.getBean(ProjectionProducerCatalog.class).projectionTypes());
		assertInstanceOf(JdbcProjectionTaskStoreAdapter.class, tasks);
		assertInstanceOf(JdbcProjectionStoreAdapter.class, writer);
	}

	@Test
	void allCanonicalProjectionTypesPublishIntoTheCanonicalStore() {
		var data = seedHistoricalPot();
		ProjectionKey readPot = readPotKey(data.potId(), 2);
		ProjectionKey balances = new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(data.potId().toString()), 2);
		ProjectionKey auth = authKey(data.potId(), 2);
		tasks.ensure(readPot, Instant.parse("2026-09-20T10:00:00Z"));
		tasks.ensure(balances, Instant.parse("2026-09-20T10:00:01Z"));
		tasks.ensure(auth, Instant.parse("2026-09-20T10:00:02Z"));

		worker.runOneCycle();
		worker.runOneCycle();
		worker.runOneCycle();

		assertTrue(reader.findProjection(readPot).isPresent());
		assertTrue(reader.findProjection(balances).isPresent());
		assertTrue(reader.findProjection(auth).isPresent());
		assertEquals(3, count("select count(*) from pocoma_read.projection_root"));
	}

	@Test
	void authIsDenseAndKeepsOneRelationPerLinkedShareholderAtTheDeletedVersion() {
		var data = seedAuthPot();
		ProjectionKey beforeDeletion = authKey(data.potId(), 2);
		ProjectionKey deletedVersion = authKey(data.potId(), 3);
		tasks.ensure(beforeDeletion, Instant.parse("2026-09-20T10:00:00Z"));
		tasks.ensure(deletedVersion, Instant.parse("2026-09-20T10:00:01Z"));

		worker.runOneCycle();
		worker.runOneCycle();

		var beforeArtifacts = reader.findProjection(beforeDeletion).orElseThrow().artifacts();
		var deletedArtifacts = reader.findProjection(deletedVersion).orElseThrow().artifacts();
		assertEquals(3, beforeArtifacts.size());
		assertEquals(beforeArtifacts, deletedArtifacts);
		assertEquals(1, beforeArtifacts.stream()
				.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.CREATOR)).count());
		assertEquals(Set.of(data.firstShareholderId().toString(), data.secondShareholderId().toString()),
				beforeArtifacts.stream()
						.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.SHAREHOLDER_USER))
						.map(artifact -> artifact.artifactKey().value()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(beforeArtifacts.stream()
				.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.SHAREHOLDER_USER))
				.allMatch(artifact -> ((JsonObject) artifact.payload()).values().get("userId")
						.equals(new JsonString(data.memberUserId().toString()))));
		assertEquals(2, count("select count(*) from pocoma_read.projection_root where projection_type='AUTH'"));
	}

	@Test
	void authVersionsMaterializeIndependentlyWhenExecutedNewestFirst() {
		var data = seedEvolvingAuthPot();
		ProjectionKey versionTwo = authKey(data.potId(), 2);
		ProjectionKey versionThree = authKey(data.potId(), 3);
		var exactReads = ExactProjectionReads.create(reader, validator);
		tasks.ensure(versionThree, Instant.parse("2026-09-20T10:00:00Z"));

		worker.runOneCycle();

		assertInstanceOf(ProjectionReadResult.Ready.class,
				exactReads.get(versionThree, AuthProjectionDefinition.DEFINITION));
		assertInstanceOf(ProjectionReadResult.NotReady.class,
				exactReads.get(versionTwo, AuthProjectionDefinition.DEFINITION));
		assertEquals(0, authTaskCount(data.potId(), 2));
		assertEquals(data.creatorUserId().toString(), projectedAuthCreator(versionThree));
		assertEquals(Map.of(
				data.firstShareholderId().toString(), data.firstMemberUserId().toString(),
				data.secondShareholderId().toString(), data.secondMemberUserId().toString()),
				projectedAuthRelations(versionThree));
		assertEquals(1, authRootCount(data.potId(), 3));
		assertEquals(0, authRootCount(data.potId(), 2));

		tasks.ensure(versionTwo, Instant.parse("2026-09-20T10:00:01Z"));
		worker.runOneCycle();

		assertInstanceOf(ProjectionReadResult.Ready.class,
				exactReads.get(versionTwo, AuthProjectionDefinition.DEFINITION));
		assertEquals(data.creatorUserId().toString(), projectedAuthCreator(versionTwo));
		assertEquals(Map.of(data.firstShareholderId().toString(), data.firstMemberUserId().toString()),
				projectedAuthRelations(versionTwo));
		assertEquals(1, authRootCount(data.potId(), 2));
		assertEquals(1, authRootCount(data.potId(), 3));
		assertEquals(2, count("select count(*) from pocoma_read.projection_root where projection_type='AUTH'"));
		assertEquals(2, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
	}

	@Test
	void realPotBalancesTaskRunsThroughLocatorGenericEngineProducerAndFencedFinalization() {
		var data = seedHistoricalPot();
		ProjectionKey key = new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(data.potId().toString()), 2);
		tasks.ensure(key, Instant.parse("2026-09-20T10:00:00Z"));

		worker.runOneCycle();

		var projection = reader.findProjection(key).orElseThrow();
		assertEquals(2, projection.artifacts().size());
		var valuesByShareholder = projection.artifacts().stream().collect(java.util.stream.Collectors.toMap(
				artifact -> artifact.artifactKey().value(),
				artifact -> (JsonObject) ((JsonObject) artifact.payload()).values().get("balance")));
		assertEquals(new JsonNumber(java.math.BigDecimal.TEN),
				valuesByShareholder.get(data.payerId().toString()).values().get("numerator"));
		assertEquals(new JsonNumber(java.math.BigDecimal.valueOf(-10)),
				valuesByShareholder.get(data.shareholderId().toString()).values().get("numerator"));
		assertCanonicalConsumptionSucceeded();
	}

	@Test
	void realReadPotTasksLoadTheExactHistoricalExpenseDateAtEachVersion() {
		var data = seedHistoricalPot();
		ProjectionKey versionOne = readPotKey(data.potId(), 1);
		ProjectionKey versionTwo = readPotKey(data.potId(), 2);
		tasks.ensure(versionOne, Instant.parse("2026-09-20T10:00:00Z"));
		tasks.ensure(versionTwo, Instant.parse("2026-09-20T10:00:01Z"));

		worker.runOneCycle();
		worker.runOneCycle();

		assertEquals(LocalDate.parse("2026-01-01"), projectedExpenseDate(versionOne));
		assertEquals(LocalDate.parse("2026-02-02"), projectedExpenseDate(versionTwo));
		assertEquals(2, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(2, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	@Test
	void canonicalProducerPersistsAndExactReadRevalidatesAndInterpretsANonTrivialReadPot() {
		var data = seedNonTrivialHistoricalPot();
		ProjectionKey key = readPotKey(data.potId(), 2);
		tasks.ensure(authKey(data.potId(), 2), Instant.parse("2026-09-20T09:59:59Z"));
		tasks.ensure(key, Instant.parse("2026-09-20T10:00:00Z"));

		worker.runOneCycle();
		worker.runOneCycle();

		var exactRead = ExactProjectionReads.create(reader, validator);
		var ready = assertInstanceOf(ReadPotResult.Ready.class,
				PotReads.create(exactRead).read(new UserId(data.creatorUserId()),
						TokenCapabilities.of(POT_VIEW), new PotId(data.potId()), 2));
		var pot = ready.pot();
		assertEquals(data.potId(), pot.potId().value());
		assertEquals(2, pot.version());
		assertEquals("Group trip", pot.name().value());
		assertEquals(Set.of(data.payerId(), data.shareholderId(), data.otherShareholderId()),
				pot.shareholders().stream().map(view -> view.shareholderId().value())
						.collect(java.util.stream.Collectors.toSet()));
		assertEquals(Set.of(data.firstExpenseId(), data.secondExpenseId()),
				pot.expenses().stream().map(view -> view.expenseId().value()).collect(java.util.stream.Collectors.toSet()));
		assertEquals(Set.of(data.payerId(), data.otherShareholderId()),
				pot.expenses().stream().map(view -> view.payerShareholderId().value()).collect(java.util.stream.Collectors.toSet()));
		assertEquals(Set.of(data.payerId(), data.shareholderId(), data.otherShareholderId()),
				pot.expenses().stream().flatMap(expense -> expense.shares().stream())
						.map(share -> share.shareholderId().value()).collect(java.util.stream.Collectors.toSet()));
		assertEquals(1, count("select count(*) from pocoma_read.projection_root where projection_type='READ_POT' "
				+ "and target_object_type='POT' and target_object_id='" + data.potId() + "' and target_version=2"));
	}

	@Test
	void terminalCanonicalProducerFailurePersistsAndExactReadReturnsFailedForTheSameKey() {
		ProjectionKey key = readPotKey(UUID.randomUUID(), 17);
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-failure-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		Instant failedAt = Instant.parse("2026-09-20T10:00:00Z");
		var processingFailure = new ProcessingFailure(new ProcessingFailureCode("IMPOSSIBLE_READ_POT"),
				"projection", "projection cannot be produced", failedAt);
		ProjectionProducerDeclaration<Void> terminalProducer = new ProjectionProducerDeclaration<>(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE,
				ReadPotProjectionDefinition.DEFINITION,
				requestedKey -> { throw new TerminalProjectionPreparationException(processingFailure, null); },
				(requestedKey, input) -> { throw new AssertionError("projector must not run"); });
		var engine = new ProjectionEngineService(new ProjectionProducerCatalog(List.of(terminalProducer)), validator);
		var execution = new ProjectionTaskConsumptionService(engine, writer, finalizer, retryHandler,
				Clock.fixed(failedAt, ZoneOffset.UTC));

		assertEquals(ProjectionTaskExecutionResult.FINALIZED, execution.execute(new ProjectionTask(key), claim));

		var result = ExactProjectionReads.create(reader, validator).get(key, ReadPotProjectionDefinition.DEFINITION);
		assertEquals(new ProjectionReadResult.Failed(key), result);
		assertEquals(1, count("select count(*) from pocoma_read.projection_failure where projection_type='READ_POT' "
				+ "and target_object_type='POT' and target_object_id='" + key.targetObjectId().value()
				+ "' and target_version=17"));
	}

	@Test
	void historicalExpenseWithoutBusinessDateFailsExplicitlyAndPublishesNothing() {
		var data = seedHistoricalPot();
		jdbc.update("update expense_headers set expense_date = null where pot_id = ? and started_at_version = 1",
				data.potId());
		ProjectionKey key = readPotKey(data.potId(), 1);
		tasks.ensure(key, Instant.parse("2026-09-20T10:00:00Z"));

		var runtimeFailure = assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,
				worker.runOneCycle());
		var cause = assertInstanceOf(HistoricalPotReconstructionException.class, runtimeFailure.cause());

		assertEquals("EXPENSE_BUSINESS_DATE_ABSENT", cause.failureCode());
		assertFalse(reader.findProjection(key).isPresent());
		assertFalse(reader.hasFailure(key));
	}

	@Test
	void canonicalPublishAndConsumptionFinalizationShareOneLocalTransaction() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		var validated = validator.validate(PotBalancesProjectionDefinition.DEFINITION,
				new Projection(key, List.of()));

		finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new Success(),
				() -> writer.publish(validated)));

		assertEquals(1, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(1, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	@Test
	void canonicalPublishRollsBackWhenFinalizationTransactionFails() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		var validated = validator.validate(PotBalancesProjectionDefinition.DEFINITION,
				new Projection(key, List.of()));

		assertThrows(IllegalStateException.class, () -> finalizer.finalizeConsumption(
				new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new Success(), () -> {
					writer.publish(validated);
					throw new IllegalStateException("force rollback after durable effect");
				})));

		assertEquals(0, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(1, count("select count(*) from consumption_slots where status='PENDING'"));
		assertEquals(1, count("select count(*) from consumption_claims where ended_at is null"));
	}

	@Test
	void canonicalProjectionFailureAndConsumptionFinalizationShareOneLocalTransaction() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		Instant failedAt = Instant.parse("2026-09-20T10:00:00.123456789Z");
		var projectionFailure = new ProjectionFailure(ProjectionFailureId.random(), key, failedAt);
		var processingFailure = new ProcessingFailure(new ProcessingFailureCode("IMPOSSIBLE_PROJECTION"),
				"projection", "projection cannot be produced", failedAt);

		finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(),
				new TerminalFailure(processingFailure), () -> writer.recordFailure(projectionFailure)));

		assertEquals(1, count("select count(*) from pocoma_read.projection_failure"));
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='FAILED'"));
		assertEquals(1, count("select count(*) from consumption_claims "
				+ "where end_reason='PROCESSING_FAILURE'"));
	}

	private ProjectionKey key() {
		return new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(UUID.randomUUID().toString()), 42);
	}

	private ProjectionKey readPotKey(UUID potId, long version) {
		return new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(potId.toString()), version);
	}

	private ProjectionKey authKey(UUID potId, long version) {
		return new ProjectionKey(AuthProjectionDefinition.PROJECTION_TYPE,
				AuthProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(potId.toString()), version);
	}

	private LocalDate projectedExpenseDate(ProjectionKey key) {
		var expense = reader.findProjection(key).orElseThrow().artifacts().stream()
				.filter(artifact -> artifact.artifactType().equals(ReadPotProjectionDefinition.EXPENSE))
				.findFirst().orElseThrow();
		return LocalDate.parse(((JsonString) ((JsonObject) expense.payload()).values().get("date")).value());
	}

	private String projectedAuthCreator(ProjectionKey key) {
		var creator = reader.findProjection(key).orElseThrow().artifacts().stream()
				.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.CREATOR))
				.findFirst().orElseThrow();
		return ((JsonString) ((JsonObject) creator.payload()).values().get("userId")).value();
	}

	private Map<String, String> projectedAuthRelations(ProjectionKey key) {
		return reader.findProjection(key).orElseThrow().artifacts().stream()
				.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.SHAREHOLDER_USER))
				.collect(java.util.stream.Collectors.toMap(
						artifact -> artifact.artifactKey().value(),
						artifact -> ((JsonString) ((JsonObject) artifact.payload()).values().get("userId")).value()));
	}

	private int authRootCount(UUID potId, long version) {
		return jdbc.queryForObject("""
				select count(*) from pocoma_read.projection_root
				where projection_type = 'AUTH' and target_object_type = 'POT'
				  and target_object_id = ? and target_version = ?
				""", Integer.class, potId.toString(), version);
	}

	private int authTaskCount(UUID potId, long version) {
		return jdbc.queryForObject("""
				select count(*) from projection_tasks
				where projection_type = 'AUTH' and target_object_type = 'POT'
				  and target_object_id = ? and target_version = ?
				""", Integer.class, potId.toString(), version);
	}

	private HistoricalData seedHistoricalPot() {
		UUID potId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 2)", potId);
		jdbc.update("insert into pot_version_metadata(pot_id, version, created_at) values (?, 1, ?), (?, 2, ?)",
				potId, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")));
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, null, 'Trip', ?, false)", UUID.randomUUID(), potId, UUID.randomUUID());
		jdbc.update("insert into shareholders(id, shareholder_id, pot_id, started_at_version, ended_at_version, "
				+ "name, weight_numerator, weight_denominator, user_id, deleted) "
				+ "values (?, ?, ?, 1, null, 'Payer', 1, 1, null, false), "
				+ "(?, ?, ?, 1, null, 'Guest', 1, 1, null, false)",
				UUID.randomUUID(), payerId, potId, UUID.randomUUID(), shareholderId, potId);
		jdbc.update("insert into expense_headers(id, expense_id, pot_id, started_at_version, ended_at_version, "
				+ "payer_id, amount_numerator, amount_denominator, label, deleted, expense_date) "
				+ "values (?, ?, ?, 1, 2, ?, 10, 1, 'Dinner', false, ?), "
				+ "(?, ?, ?, 2, null, ?, 10, 1, 'Dinner', false, ?)",
				UUID.randomUUID(), expenseId, potId, payerId, LocalDate.parse("2026-01-01"),
				UUID.randomUUID(), expenseId, potId, payerId, LocalDate.parse("2026-02-02"));
		jdbc.update("insert into expense_shares(id, expense_id, shareholder_id, pot_id, started_at_version, "
				+ "ended_at_version, weight_numerator, weight_denominator) values (?, ?, ?, ?, 1, null, 1, 1)",
				UUID.randomUUID(), expenseId, shareholderId, potId);
		return new HistoricalData(potId, payerId, shareholderId);
	}

	private NonTrivialHistoricalData seedNonTrivialHistoricalPot() {
		UUID potId = UUID.randomUUID();
		UUID creatorUserId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		UUID otherShareholderId = UUID.randomUUID();
		UUID firstExpenseId = UUID.randomUUID();
		UUID secondExpenseId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 2)", potId);
		jdbc.update("insert into pot_version_metadata(pot_id, version, created_at) values (?, 1, ?), (?, 2, ?)",
				potId, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")));
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, null, 'Group trip', ?, false)", UUID.randomUUID(), potId, creatorUserId);
		jdbc.update("insert into shareholders(id, shareholder_id, pot_id, started_at_version, ended_at_version, "
				+ "name, weight_numerator, weight_denominator, user_id, deleted) values "
				+ "(?, ?, ?, 1, null, 'Alice', 1, 3, ?, false), "
				+ "(?, ?, ?, 1, null, 'Bob', 1, 3, ?, false), "
				+ "(?, ?, ?, 1, null, 'Chloe', 1, 3, null, false)",
				UUID.randomUUID(), payerId, potId, UUID.randomUUID(),
				UUID.randomUUID(), shareholderId, potId, UUID.randomUUID(),
				UUID.randomUUID(), otherShareholderId, potId);
		jdbc.update("insert into expense_headers(id, expense_id, pot_id, started_at_version, ended_at_version, "
				+ "payer_id, amount_numerator, amount_denominator, label, deleted, expense_date) values "
				+ "(?, ?, ?, 1, null, ?, 45, 1, 'Dinner', false, ?), "
				+ "(?, ?, ?, 2, null, ?, 30, 1, 'Tickets', false, ?)",
				UUID.randomUUID(), firstExpenseId, potId, payerId, LocalDate.parse("2026-02-01"),
				UUID.randomUUID(), secondExpenseId, potId, otherShareholderId, LocalDate.parse("2026-02-02"));
		jdbc.update("insert into expense_shares(id, expense_id, shareholder_id, pot_id, started_at_version, "
				+ "ended_at_version, weight_numerator, weight_denominator) values "
				+ "(?, ?, ?, ?, 1, null, 1, 2), (?, ?, ?, ?, 1, null, 1, 2), "
				+ "(?, ?, ?, ?, 2, null, 1, 3), (?, ?, ?, ?, 2, null, 1, 3), (?, ?, ?, ?, 2, null, 1, 3)",
				UUID.randomUUID(), firstExpenseId, payerId, potId,
				UUID.randomUUID(), firstExpenseId, shareholderId, potId,
				UUID.randomUUID(), secondExpenseId, payerId, potId,
				UUID.randomUUID(), secondExpenseId, shareholderId, potId,
				UUID.randomUUID(), secondExpenseId, otherShareholderId, potId);
		return new NonTrivialHistoricalData(potId, creatorUserId, payerId, shareholderId, otherShareholderId,
				firstExpenseId, secondExpenseId);
	}

	private AuthHistoricalData seedAuthPot() {
		UUID potId = UUID.randomUUID();
		UUID creatorUserId = UUID.randomUUID();
		UUID memberUserId = UUID.randomUUID();
		UUID firstShareholderId = UUID.randomUUID();
		UUID secondShareholderId = UUID.randomUUID();
		UUID unlinkedShareholderId = UUID.randomUUID();
		UUID deletedShareholderId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 3)", potId);
		jdbc.update("insert into pot_version_metadata(pot_id, version, created_at) values "
				+ "(?, 1, ?), (?, 2, ?), (?, 3, ?)",
				potId, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-03-03T10:00:00Z")));
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, 3, 'Trip', ?, false), (?, ?, 3, null, 'Trip', ?, true)",
				UUID.randomUUID(), potId, creatorUserId, UUID.randomUUID(), potId, creatorUserId);
		jdbc.update("insert into shareholders(id, shareholder_id, pot_id, started_at_version, ended_at_version, "
				+ "name, weight_numerator, weight_denominator, user_id, deleted) values "
				+ "(?, ?, ?, 1, null, 'First', 1, 1, ?, false), "
				+ "(?, ?, ?, 1, null, 'Second', 1, 1, ?, false), "
				+ "(?, ?, ?, 1, null, 'Unlinked', 1, 1, null, false), "
				+ "(?, ?, ?, 1, null, 'Deleted', 1, 1, ?, true)",
				UUID.randomUUID(), firstShareholderId, potId, memberUserId,
				UUID.randomUUID(), secondShareholderId, potId, memberUserId,
				UUID.randomUUID(), unlinkedShareholderId, potId,
				UUID.randomUUID(), deletedShareholderId, potId, UUID.randomUUID());
		return new AuthHistoricalData(potId, creatorUserId, memberUserId,
				firstShareholderId, secondShareholderId);
	}

	private EvolvingAuthHistoricalData seedEvolvingAuthPot() {
		UUID potId = UUID.randomUUID();
		UUID creatorUserId = UUID.randomUUID();
		UUID firstMemberUserId = UUID.randomUUID();
		UUID secondMemberUserId = UUID.randomUUID();
		UUID firstShareholderId = UUID.randomUUID();
		UUID secondShareholderId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 3)", potId);
		jdbc.update("insert into pot_version_metadata(pot_id, version, created_at) values "
				+ "(?, 1, ?), (?, 2, ?), (?, 3, ?)",
				potId, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-03-03T10:00:00Z")));
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, null, 'Trip', ?, false)", UUID.randomUUID(), potId, creatorUserId);
		jdbc.update("insert into shareholders(id, shareholder_id, pot_id, started_at_version, ended_at_version, "
				+ "name, weight_numerator, weight_denominator, user_id, deleted) values "
				+ "(?, ?, ?, 2, null, 'First', 1, 1, ?, false), "
				+ "(?, ?, ?, 3, null, 'Second', 1, 1, ?, false)",
				UUID.randomUUID(), firstShareholderId, potId, firstMemberUserId,
				UUID.randomUUID(), secondShareholderId, potId, secondMemberUserId);
		return new EvolvingAuthHistoricalData(potId, creatorUserId, firstMemberUserId, secondMemberUserId,
				firstShareholderId, secondShareholderId);
	}

	private void assertCanonicalConsumptionSucceeded() {
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(1, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private record HistoricalData(UUID potId, UUID payerId, UUID shareholderId) {}
	private record NonTrivialHistoricalData(UUID potId, UUID creatorUserId, UUID payerId, UUID shareholderId,
			UUID otherShareholderId, UUID firstExpenseId, UUID secondExpenseId) {}
	private record AuthHistoricalData(UUID potId, UUID creatorUserId, UUID memberUserId,
			UUID firstShareholderId, UUID secondShareholderId) {}
	private record EvolvingAuthHistoricalData(UUID potId, UUID creatorUserId, UUID firstMemberUserId,
			UUID secondMemberUserId, UUID firstShareholderId, UUID secondShareholderId) {}
}
