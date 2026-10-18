package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.contracts.command.*;
import com.kartaguez.pocoma.domain.authorization.*;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.domain.consumption.lifecycle.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.engine.consume.command.consumption.CommandConsumptionExecution;
import com.kartaguez.pocoma.engine.consume.command.pot.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.engine.write.pot.context.*;
import com.kartaguez.pocoma.engine.write.pot.exception.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.JpaPotGlobalVersionAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context.JpaPotContextAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.supra.consume.command.*;

/** Real COMMAND owners and Spring persistence proxies, coordinated before the real Pot CAS. */
@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
    "pocoma.command-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PotCasConsumptionPostgresTest.Configuration.class)
class PotCasConsumptionPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired RecordedCommandInsertionPort commands;
    @Autowired @Qualifier("commandConsumptionTransactionRunner") TransactionRunner transactions;
    @Autowired @Qualifier("commandConsumptionAcquire") AcquireConsumptionUseCase acquire;
    @Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute;
    @Autowired @Qualifier("commandConsumptionHandleFailure") HandleConsumptionFailureUseCase failure;
    @Autowired CommandConsumptionLocator locator;
    @Autowired CommandConsumptionExecution execution;
    @Autowired JpaConsumptionLifecycleAdapter lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired Gate gate;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired JpaPotGlobalVersionAdapter realVersions;
    @MockitoBean(name = "commandConsumptionClock") Clock clock;
    final AtomicReference<Instant> now = new AtomicReference<>();
    final List<RuntimeException> escaped = new CopyOnWriteArrayList<>();
    ConsumptionOrchestrator orchestrator;

    @BeforeEach void setup() {
        now.set(Instant.now().plusSeconds(1));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        gate.reset(); escaped.clear();
        // Observe the real public owner return boundary without substituting its behavior.
        orchestrator = new SequentialConsumptionOrchestrator(locator, acquire, input -> {
            try { return execute.execute(input); }
            catch (RuntimeException error) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                escaped.add(error); throw error;
            }
        }, failure, lifecycle);
    }

    @Test void realCasRollsBackThenNewClaimReloadsVWithImmutableOAndSucceeds() throws Exception { race(false); }
    @Test void realCasRollsBackThenNewContextRejectsObsoleteCommandOnce() throws Exception { race(true); }

    private void race(boolean obsolete) throws Exception {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var losing = details(pot, user, "losing"); insert(losing);
        gate.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> trackedRun(losing));
            assertTrue(gate.loaded.await(10, TimeUnit.SECONDS));
            var firstClaim = lifecycle.findClaims(slot(losing).slotId()).getFirst();
            assertEquals(1L, gate.versions.getFirst());
            var winning = obsolete ? details(pot, user, "winner") : command(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
                "{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Member\",\"weightNumerator\":1,\"weightDenominator\":1}],\"observedVersion\":1}".formatted(pot),
                user, Set.of(PocomaPermissions.SHAREHOLDER_CREATE), Instant.now().plusSeconds(600));
            insert(winning); runOne();
            assertEquals(TerminalOutcome.SUCCESS, slot(winning).terminalOutcome().orElseThrow());
            gate.release.countDown(); pending.get(10, TimeUnit.SECONDS);
            assertInstanceOf(PotVersionCasConflictException.class, escaped.getFirst());
            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), gate.completions);
            var retrySlot = slot(losing);
            assertTrue(retrySlot.terminalOutcome().isEmpty());
            assertTrue(retrySlot.currentClaimId().isEmpty());
            assertTrue(firstClaim.endedAt().isEmpty()); // immutable acquired snapshot
            var failed = lifecycle.findClaims(retrySlot.slotId()).getFirst();
            assertEquals("TRANSIENT", failed.failure().orElseThrow().category());
            assertEquals("POT_VERSION_CAS_CONFLICT", failed.failure().orElseThrow().code().value());
            assertEquals(failed.endedAt().orElseThrow().plusSeconds(1), retrySlot.nextClaimAt());
            assertNoTerminal(losing);
            assertEquals(0, count("consumption_inputs", "slot_id", retrySlot.slotId()));
            assertEquals(0, count("consumption_results", "slot_id", retrySlot.slotId()));
            assertEquals(2L, version(pot));
            assertEquals(2, count("business_event_outbox", "pot_id", pot));
            assertEquals(2, count("pot_version_metadata", "pot_id", pot));
            assertEquals(obsolete ? 2 : 1, count("pot_headers", "pot_id", pot));
            runOne(); // not yet eligible: no immediate retry
            assertEquals(1, lifecycle.findClaims(retrySlot.slotId()).size());
            now.set(retrySlot.nextClaimAt()); trackedRun(losing);
            var claims = lifecycle.findClaims(retrySlot.slotId());
            assertEquals(2, claims.size()); assertNotEquals(claims.get(0).claimId(), claims.get(1).claimId());
            assertEquals(List.of(1L, 2L), gate.versions);
            assertNotEquals(gate.xids.get(0), gate.xids.get(1));
            assertEquals(List.of(1L, 1L), gate.observed);
            assertEquals(losing.serializedPayload(), jdbc.queryForObject("select payload_json from recorded_commands where command_id=?", String.class, losing.commandId().value()));
            assertEquals(obsolete ? TerminalOutcome.REJECTED : TerminalOutcome.SUCCESS, slot(losing).terminalOutcome().orElseThrow());
            if (obsolete) assertEquals(new TerminalReason("OBSOLETE_COMMAND"), slot(losing).terminalReason().orElseThrow());
            assertEquals(obsolete ? 2L : 3L, version(pot));
            assertEquals(obsolete ? 2 : 3, count("business_event_outbox", "pot_id", pot));
            assertEquals(1, count("command_outcomes", "command_id", losing.commandId().value()));
            assertEquals(1, count("command_terminal_events", "command_id", losing.commandId().value()));
            assertEquals(obsolete ? 0 : 1,count("consumption_results","slot_id",retrySlot.slotId()));
            if (!obsolete) assertEquals(1,jdbc.queryForObject(
                "select count(*) from consumption_results r join business_event_outbox e on e.id::text=r.object_id where r.slot_id=? and e.pot_id=? and e.version=3",
                Integer.class,retrySlot.slotId(),pot));
            trackedRun(losing); assertEquals(2, lifecycle.findClaims(retrySlot.slotId()).size());
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void temporalCardinalityAnomalyRollsBackAndFailsTechnicallyWithoutRetry() {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var command = details(pot, user, "cardinality"); insert(command);
        gate.corruptCardinality = true; trackedRun(command);
        assertInstanceOf(AggregateTransitionConsistencyException.class, escaped.getFirst());
        assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), gate.completions);
        assertEquals(TerminalOutcome.FAILED, slot(command).terminalOutcome().orElseThrow());
        assertEquals(1L, version(pot));
        assertEquals(1, count("business_event_outbox", "pot_id", pot));
        assertEquals(1, count("pot_headers", "pot_id", pot));
        assertEquals(1, count("pot_version_metadata", "pot_id", pot));
        var claims = lifecycle.findClaims(slot(command).slotId()); assertEquals(1, claims.size());
        assertEquals("COMMAND_TECHNICAL_INVARIANT", claims.getFirst().failure().orElseThrow().category());
    }

    @Test void expectedMissingReferenceRejectsDurablyButUnexpectedHeaderAbsenceRollsBack() {
        UUID user = UUID.randomUUID();
        var missing = details(UUID.randomUUID(), user, "missing"); insert(missing); runOne();
        assertEquals(TerminalOutcome.REJECTED, slot(missing).terminalOutcome().orElseThrow());
        assertEquals(new TerminalReason("POT_GLOBAL_VERSION"), slot(missing).terminalReason().orElseThrow());
        assertEquals(1, count("command_outcomes", "command_id", missing.commandId().value()));
        var missingExpense = command(PotCommandTypes.EXPENSE_DELETE_V1,
            "{\"expenseId\":\"%s\",\"observedVersion\":1}".formatted(UUID.randomUUID()),user,
            Set.of(PocomaPermissions.EXPENSE_DELETE),Instant.now().plusSeconds(600));
        insert(missingExpense); runOne();
        assertEquals(TerminalOutcome.REJECTED,slot(missingExpense).terminalOutcome().orElseThrow());
        assertEquals(new TerminalReason("EXPENSE_HEADER"),slot(missingExpense).terminalReason().orElseThrow());
        assertEquals(1,lifecycle.findClaims(slot(missingExpense).slotId()).size());
        UUID pot = createPot(user); var technical = details(pot, user, "technical"); insert(technical);
        gate.removeHeader = true; trackedRun(technical);
        assertInstanceOf(BusinessEntityNotFoundException.class, escaped.getFirst());
        assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), gate.completions);
        assertEquals(TerminalOutcome.FAILED, slot(technical).terminalOutcome().orElseThrow());
        assertEquals(1L, version(pot)); assertEquals(1, count("pot_headers", "pot_id", pot));
        assertEquals(1, count("business_event_outbox", "pot_id", pot));
    }

    @Test void takeoverFencesOldAttemptIncludingItsFlushedBusinessEventAndWinnerCanContinue() throws Exception {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var command = details(pot, user, "takeover"); insert(command); gate.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> trackedRun(command)); assertTrue(gate.loaded.await(10, TimeUnit.SECONDS));
            var old = lifecycle.findClaims(slot(command).slotId()).getFirst();
            var winner = assertInstanceOf(AcquireResult.Acquired.class, transactions.runInTransaction(() ->
                lifecycle.acquire(CommandConsumptionKeys.forCommand(command.commandId()), ClaimId.generate(),
                    new WorkerId("takeover"), new ClaimLease(Duration.ofSeconds(30)), old.leaseUntil()))).claim();
            gate.release.countDown(); pending.get(10, TimeUnit.SECONDS);
            assertInstanceOf(com.kartaguez.pocoma.engine.exception.consumption.LostClaimException.class, escaped.getFirst());
            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), gate.completions);
            assertNoTerminal(command); assertEquals(1L, version(pot));
            assertEquals(1, count("business_event_outbox", "pot_id", pot));
            assertEquals(winner.claimId(), slot(command).currentClaimId().orElseThrow());
            now.set(old.leaseUntil());
            execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), execution.forCommand(command.commandId())));
            assertEquals(TerminalOutcome.SUCCESS, slot(command).terminalOutcome().orElseThrow());
            assertEquals(2L, version(pot)); assertEquals(2, count("business_event_outbox", "pot_id", pot));
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void fourRealCasLossesUseExistingOneFiveThirtySecondBudgetThenFailTechnically() throws Exception {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var losing = details(pot, user, "budget"); insert(losing);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                gate.loaded = new CountDownLatch(1); gate.release = new CountDownLatch(1); gate.arm();
                var pending = pool.submit(() -> trackedRun(losing));
                assertTrue(gate.loaded.await(10, TimeUnit.SECONDS));
                insert(command(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
                    "{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Member\",\"weightNumerator\":1,\"weightDenominator\":1}],\"observedVersion\":1}".formatted(pot),
                    user, Set.of(PocomaPermissions.SHAREHOLDER_CREATE), Instant.now().plusSeconds(600)));
                runOne(); gate.release.countDown(); pending.get(10, TimeUnit.SECONDS);
                var slot = slot(losing); var claims = lifecycle.findClaims(slot.slotId());
                assertEquals(attempt, claims.size());
                assertEquals("TRANSIENT", claims.getLast().failure().orElseThrow().category());
                if (attempt < 4) {
                    assertNoTerminal(losing);
                    assertEquals(now.get().plusSeconds(new long[] {1,5,30}[attempt-1]), slot.nextClaimAt());
                    now.set(slot.nextClaimAt());
                } else {
                    assertEquals(TerminalOutcome.FAILED, slot.terminalOutcome().orElseThrow());
                    assertEquals(1, count("command_outcomes", "command_id", losing.commandId().value()));
                    assertEquals(1, count("command_terminal_events", "command_id", losing.commandId().value()));
                }
            }
            assertEquals(4, escaped.size()); escaped.forEach(error -> assertInstanceOf(PotVersionCasConflictException.class,error));
            assertEquals(5L, version(pot)); assertEquals(5, count("business_event_outbox", "pot_id", pot));
            trackedRun(losing); assertEquals(4, lifecycle.findClaims(slot(losing).slotId()).size());
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void bindingFenceLossKeepsDistinctRecoveryAndRollsBackBeforeRetry() throws Exception {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var command = details(pot,user,"binding"); insert(command); gate.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> trackedRun(command)); assertTrue(gate.loaded.await(10, TimeUnit.SECONDS));
            var identity = command.envelope().externalIdentity();
            jdbc.update("update external_identity_binding_streams set current_revision=current_revision+1 where issuer=? and subject=?", identity.issuer(),identity.subject());
            gate.release.countDown(); pending.get(10, TimeUnit.SECONDS);
            assertInstanceOf(com.kartaguez.pocoma.engine.consume.command.execution.BindingFenceConflictException.class, escaped.getFirst());
            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), gate.completions);
            assertNoTerminal(command); assertEquals(1L,version(pot));
            assertEquals(1,count("business_event_outbox","pot_id",pot));
            assertEquals("BINDING_FENCE_CONFLICT", lifecycle.findClaims(slot(command).slotId()).getFirst().failure().orElseThrow().code().value());
            now.set(slot(command).nextClaimAt()); trackedRun(command);
            assertEquals(TerminalOutcome.SUCCESS, slot(command).terminalOutcome().orElseThrow());
            assertEquals(2,lifecycle.findClaims(slot(command).slotId()).size());
            assertEquals(2,count("business_event_outbox","pot_id",pot));
        } finally { gate.release.countDown(); pool.shutdownNow(); }
    }

    @Test void committedOrUnknownResolutionWithCasCauseNeverSchedulesRetryOrPublishesFailed() {
        for (var outcome : List.of(
                com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.COMMITTED,
                com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.UNKNOWN)) {
            UUID user = UUID.randomUUID(); UUID pot = createPot(user);
            var command = details(pot,user,"resolution"); insert(command);
            ConsumptionOrchestrator uncertain = new SequentialConsumptionOrchestrator(locator, acquire, input -> {
                if (outcome == com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.COMMITTED)
                    execute.execute(input); // real TE commit, then injected observation failure
                throw new com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException(outcome,
                    com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Phase.COMMIT,
                    new PotVersionCasConflictException("injected resolution cause"));
            }, failure, lifecycle);
            var result = uncertain.run(new ConsumptionOrchestrationInput(new WorkerId("resolution"),
                new ClaimLease(Duration.ofSeconds(30)),new ConsumptionOrchestrationBudget(100,1)));
            assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,result);
            assertEquals(1,lifecycle.findClaims(slot(command).slotId()).size());
            if (outcome == com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.COMMITTED) {
                assertEquals(TerminalOutcome.SUCCESS,slot(command).terminalOutcome().orElseThrow());
                assertEquals(2L,version(pot));
            } else {
                assertNoTerminal(command); assertTrue(slot(command).currentClaimId().isPresent());
                assertTrue(lifecycle.findClaims(slot(command).slotId()).getFirst().failure().isEmpty());
                assertEquals(1L,version(pot));
                // Close the intentionally unresolved fixture through the existing winning claim.
                var claim = lifecycle.findClaims(slot(command).slotId()).getFirst();
                execute.execute(new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),execution.forCommand(command.commandId())));
            }
        }
    }

    @Test void replacedResolutionErrorRetainsRealJpaCasCauseAfterConfirmedPostgresRollback() {
        UUID user = UUID.randomUUID(); UUID pot = createPot(user);
        var winner = details(pot,user,"winner"); insert(winner); runOne();
        var spring = new com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner(new TransactionTemplate(manager));
        TransactionRunner replacement = new TransactionRunner() {
            @Override public <T> T runInTransaction(java.util.function.Supplier<T> action) {
                try { return spring.runInTransaction(action); }
                catch (PotVersionCasConflictException original) { throw new IllegalStateException("injected resolution observer failure"); }
            }
            @Override public void runAfterCommit(Runnable action) { spring.runAfterCommit(action); }
        };
        var owner = new com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner(replacement,true);
        var resolution = assertThrows(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.class,
            () -> owner.runInTransaction(() -> {
                jdbc.update("update pot_headers set label='rolled back' where pot_id=?",pot);
                realVersions.updateIfActive(new PotGlobalVersion(PotId.of(pot),1),new PotGlobalVersion(PotId.of(pot),2));
            }));
        assertEquals(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.ROLLED_BACK,resolution.outcome());
        assertInstanceOf(PotVersionCasConflictException.class,resolution.getCause().getSuppressed()[0]);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(0,jdbc.queryForObject("select count(*) from pot_headers where pot_id=? and label='rolled back'",Integer.class,pot));
        assertEquals("TRANSIENT",new com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionTechnicalFailureClassifier(clock).classify(resolution).category());
        assertEquals(2L,version(pot));
    }

    private void trackedRun(RecordedCommand command) { gate.track.set(true); gate.command.set(command.commandId().value()); try { runOne(); } finally { gate.track.remove(); gate.command.remove(); } }
    private void runOne() {
        var result = orchestrator.run(new ConsumptionOrchestrationInput(new WorkerId("step2b"),
            new ClaimLease(Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(100, 1)));
        assertFalse(result instanceof ConsumptionOrchestrationResult.RuntimeFailure, () -> result.toString());
    }
    private UUID createPot(UUID user) {
        String label = "step2b-" + UUID.randomUUID();
        var command = command(PotCommandTypes.POT_CREATE_V1, "{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(label,user),
            user, Set.of(PocomaPermissions.POT_CREATE), Instant.now().plusSeconds(600));
        insert(command); runOne(); assertEquals(TerminalOutcome.SUCCESS, slot(command).terminalOutcome().orElseThrow());
        return jdbc.queryForObject("select pot_id from pot_headers where label=?", UUID.class, label);
    }
    private RecordedCommand details(UUID pot, UUID user, String label) {
        return command(PotCommandTypes.POT_DETAILS_UPDATE_V1,
            "{\"potId\":\"%s\",\"label\":\"%s\",\"observedVersion\":1}".formatted(pot,label), user,
            Set.of(PocomaPermissions.POT_UPDATE), Instant.now().plusSeconds(600));
    }
    private void insert(RecordedCommand command) { transactions.runInTransaction(() -> commands.insert(command)); }
    private ConsumptionSlot slot(RecordedCommand command) { return lifecycle.findSlot(CommandConsumptionKeys.forCommand(command.commandId())).orElseThrow(); }
    private long version(UUID pot) { return jdbc.queryForObject("select version from pot_global_versions where pot_id=?", Long.class, pot); }
    private int count(String table, String key, UUID id) { return jdbc.queryForObject("select count(*) from " + table + " where " + key + "=?", Integer.class, id); }
    private void assertNoTerminal(RecordedCommand command) {
        assertEquals(0, count("command_outcomes", "command_id", command.commandId().value()));
        assertEquals(0, count("command_terminal_events", "command_id", command.commandId().value()));
    }
	private RecordedCommand command(CommandType type, String payload, UUID userId,
			Set<Permission> permissions, Instant validUntil) {
		ExternalIdentity identity = new ExternalIdentity("runtime-test", "subject-" + UUID.randomUUID());
		BindingId binding = createBinding(identity, userId);
		Set<String> authorities = permissions.stream()
				.map(permission -> "pocoma:" + permission.objectType().toLowerCase(Locale.ROOT)
						+ ":" + permission.action().toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		return new RecordedCommand(new CommandId(UUID.randomUUID()), type, payload, Instant.now(),
				new TargetCommandEnvelope(identity, binding,
						new CommandAuthenticationEvidence(authorities, validUntil)));
	}

	private BindingId createBinding(ExternalIdentity identity, UUID userId) {
		UUID binding = UUID.randomUUID();
		jdbc.update("insert into users(user_id) values (?) on conflict do nothing", userId);
		jdbc.update("insert into external_identity_binding_streams(issuer,subject,current_revision) values (?,?,0)",
				identity.issuer(), identity.subject());
		jdbc.update("""
				insert into external_identity_binding_occurrences
				(binding_id,issuer,subject,user_id,attached_revision,created_at) values (?,?,?,?,0,now())
				""", binding, identity.issuer(), identity.subject(), userId);
		jdbc.update("insert into external_identities(issuer,subject,user_id,binding_id) values (?,?,?,?)",
				identity.issuer(), identity.subject(), userId, binding);
		return new BindingId(binding);
	}

    static class Gate {
        final ThreadLocal<UUID> command = new ThreadLocal<>();
        final ThreadLocal<Boolean> track = ThreadLocal.withInitial(() -> false);
        final List<Long> versions = new CopyOnWriteArrayList<>(), xids = new CopyOnWriteArrayList<>(), observed = new CopyOnWriteArrayList<>();
        final List<Integer> completions = new CopyOnWriteArrayList<>();
        final AtomicBoolean first = new AtomicBoolean();
        CountDownLatch loaded, release;
        boolean corruptCardinality, removeHeader;
        void reset() { versions.clear(); xids.clear(); observed.clear(); completions.clear(); first.set(false); loaded = new CountDownLatch(1); release = new CountDownLatch(1); corruptCardinality = false; removeHeader = false; }
        void arm() { first.set(true); }
        void context(PotGlobalVersion version, JdbcTemplate jdbc) {
            if (!track.get()) return;
            versions.add(version.version()); xids.add(jdbc.queryForObject("select txid_current()", Long.class));
            observed.add(jdbc.queryForObject("select (payload_json::jsonb ->> 'observedVersion')::bigint from recorded_commands where command_id=?", Long.class, command.get()));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completions.add(status); }
            });
            if (removeHeader) jdbc.update("delete from pot_headers where pot_id=?", version.potId().value());
        }
        void beforeCas() {
            if (!track.get() || !first.compareAndSet(true, false)) return;
            loaded.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class Configuration {
        @Bean Gate gate() { return new Gate(); }
        @Bean @Primary PotGlobalVersionPort coordinatedVersions(JpaPotGlobalVersionAdapter delegate, Gate gate, JdbcTemplate jdbc) {
            return new PotGlobalVersionPort() {
                @Override public void save(PotGlobalVersion value) { delegate.save(value); }
                @Override public void updateIfActive(PotGlobalVersion current, PotGlobalVersion next) {
                    gate.beforeCas(); delegate.updateIfActive(current,next);
                    if (gate.track.get() && gate.corruptCardinality)
                        jdbc.update("update pot_headers set ended_at_version=? where pot_id=? and ended_at_version is null", next.version(), current.potId().value());
                }
            };
        }
        @Bean @Primary PotContextPort coordinatedContexts(JpaPotContextAdapter delegate, Gate gate, JdbcTemplate jdbc) {
            return new PotContextPort() {
                @Override public Optional<UpdatePotDetailsContext> loadUpdatePotDetailsContext(PotId pot) {
                    var context = delegate.loadUpdatePotDetailsContext(pot);
                    context.ifPresent(value -> gate.context(value.potGlobalVersion(), jdbc)); return context;
                }
                @Override public Optional<AddPotShareholdersContext> loadAddPotShareholdersContext(PotId pot) { return delegate.loadAddPotShareholdersContext(pot); }
                @Override public Optional<CreateExpenseContext> loadCreateExpenseContext(PotId pot) { return delegate.loadCreateExpenseContext(pot); }
                @Override public Optional<DeletePotContext> loadDeletePotContext(PotId pot) { return delegate.loadDeletePotContext(pot); }
                @Override public Optional<UpdatePotShareholdersDetailsContext> loadUpdatePotShareholdersDetailsContext(PotId pot) { return delegate.loadUpdatePotShareholdersDetailsContext(pot); }
                @Override public Optional<UpdatePotShareholdersWeightsContext> loadUpdatePotShareholdersWeightsContext(PotId pot) { return delegate.loadUpdatePotShareholdersWeightsContext(pot); }
            };
        }
    }
}
