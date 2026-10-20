package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.port.transaction.EventFamily.*;
import static com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.*;
import static com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Phase.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.domain.consumption.key.*;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.*;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.port.transaction.*;
import com.kartaguez.pocoma.runtime.consumption.*;
import com.kartaguez.pocoma.runtime.command.consumption.eventprotocol.PrimaryEventProtocolTestConfiguration.*;

/** DB CURRENT V1-V29 plus isolated synthetic suffix tables. No certified baseline or real 4C E. */
@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
        "pocoma.command-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate" })
@Import(PrimaryEventProtocolTestConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrimaryEventTransactionPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired ApplicationContext context;
    JpaTransactionManager manager;
    @Autowired void manager(PlatformTransactionManager value) { manager = (JpaTransactionManager) value; }
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @Autowired PrimaryEventTransactionRunner runner;
    @Autowired PublishedEvents declarations;
    @Autowired SyntheticWriter writer;
    @Autowired SyntheticSuffix suffix;
    @Autowired @Qualifier("step4bConsumptionOwner") TransactionRunner owner;
    @Autowired @Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle;
    @Autowired @Qualifier("commandConsumptionTerminalParticipant") ConsumptionTerminalParticipant terminal;
    private final List<Integer> outcomes = new ArrayList<>();

    @BeforeEach void setup() {
        assertClean();
        suffix.calls = 0; suffix.last = null; suffix.failAfterAssociation = false;
        jdbc.execute("create schema if not exists experiment_4b");
        jdbc.execute("create table if not exists experiment_4b.accepted(family text,id uuid,primary key(family,id))");
        jdbc.execute("create table if not exists experiment_4b.e_access(ord bigserial,family text)");
        jdbc.execute("create table if not exists experiment_4b.association(ord bigserial,family text,id uuid,unique(family,id),foreign key(family,id) references experiment_4b.accepted(family,id))");
        jdbc.execute("create table if not exists experiment_4b.audit(ord bigserial,phase text,pid int,xid bigint)");
        jdbc.execute("""
            create or replace function experiment_4b.observe() returns trigger language plpgsql as $$
            begin insert into experiment_4b.audit(phase,pid,xid)
              values(TG_TABLE_NAME||':'||TG_OP,pg_backend_pid(),txid_current()); return NEW; end $$
            """);
        for (String table : List.of("business_event_outbox", "consumption_slots", "consumption_claims",
                "experiment_4b.e_access", "experiment_4b.association")) {
            jdbc.execute("drop trigger if exists step4b_observe on " + table);
            jdbc.execute("create trigger step4b_observe after insert or update on " + table
                    + " for each row execute function experiment_4b.observe()");
        }
        jdbc.execute("truncate experiment_4b.association, experiment_4b.accepted, experiment_4b.e_access, experiment_4b.audit restart identity");
    }
    @AfterEach void cleanup() { assertClean(); }
    void assertClean() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), TransactionSynchronizationManager.getResourceMap().toString());
    }
    void observe() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { outcomes.add(status); }
        });
    }
    void mark(String phase) {
        jdbc.update("insert into experiment_4b.audit(phase,pid,xid) values (?,pg_backend_pid(),txid_current())", phase);
    }
    EventRef publish(EventFamily family, UUID id) {
        mark("request");
        EventRef accepted = writer.accept(family, id);
        mark("acceptance");
        declarations.recordPublishedEvent(accepted);
        mark("declaration");
        return accepted;
    }
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    List<EventRef> refs(String table) {
        return jdbc.query("select family,id from experiment_4b." + table + " order by ord",
                (rs, n) -> new EventRef(EventFamily.valueOf(rs.getString(1)), rs.getObject(2, UUID.class)));
    }
    void assertRolledBack() {
        assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), outcomes);
        assertEquals(0, count("experiment_4b.accepted"));
        assertEquals(0, count("experiment_4b.e_access"));
        assertEquals(0, count("experiment_4b.association"));
        assertEquals(0, count("experiment_4b.audit"));
    }

    @Test void standardManagerAndDeferredJpaJdbcSharePhysicalPidXidAndCommitOnlyAfterSuffix() throws Exception {
        assertEquals(JpaTransactionManager.class, manager.getClass());
        assertFalse(manager.isNestedTransactionAllowed());
        assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
        UUID id = UUID.randomUUID();
        owner.runInTransaction(() -> {
            observe();
            Object[] jpa = (Object[]) EntityManagerFactoryUtils.getTransactionalEntityManager(emf)
                    .createNativeQuery("select pg_backend_pid(),txid_current()").getSingleResult();
            var sql = jdbc.queryForMap("select pg_backend_pid() pid,txid_current() xid");
            assertEquals(((Number) jpa[0]).longValue(), ((Number) sql.get("pid")).longValue());
            assertEquals(((Number) jpa[1]).longValue(), ((Number) sql.get("xid")).longValue());
            publish(BUSINESS_EVENT, id);
            assertEquals(0, jdbc.queryForObject("select count(*) from business_event_outbox where id=?", Integer.class, id), "persist has not flushed");
            runner.runAfterCommit(() -> assertEquals(1, independentCount(id)));
            assertEquals(0, independentCount(id), "no public success before commit");
        });
        assertEquals(1, independentCount(id));
        assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED), outcomes);
        assertEquals(1, suffix.calls);
        assertEquals(List.of(new EventRef(BUSINESS_EVENT, id)), refs("association"));
        assertEquals(1, jdbc.queryForObject("select count(distinct(pid,xid)) from experiment_4b.audit", Integer.class));
        assertEquals(List.of("request", "acceptance", "declaration", "business_event_outbox:INSERT", "e_access:INSERT", "association:INSERT"),
                jdbc.queryForList("select phase from experiment_4b.audit order by ord", String.class));
        System.out.println("STEP4B PHYSICAL " + jdbc.queryForList("select * from experiment_4b.audit order by ord"));
    }
    int independentCount(UUID id) {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var q = c.prepareStatement("select count(*) from business_event_outbox where id=?")) {
            q.setObject(1, id); try (var rs = q.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public static class Participant {
        private final Runnable action;
        Participant(Runnable action) { this.action = action; }
        @Transactional public void required() { action.run(); }
        @Transactional(propagation = Propagation.MANDATORY) public void mandatory() { action.run(); }
    }
    @SuppressWarnings("deprecation") Participant participant(Runnable action) {
        var proxy = new ProxyFactory(new Participant(action));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (Participant) proxy.getProxy();
    }
    @Test void multipleRunnerRequiredMandatoryParticipantsContributeWithoutClosingOrFlushing() {
        List<EventRef> accepted = new ArrayList<>();
        var participant = participant(() -> {
            assertEquals(0, suffix.calls);
            accepted.add(publish(BUSINESS_EVENT, UUID.randomUUID()));
            assertEquals(0, jdbc.queryForObject("select count(*) from business_event_outbox where id=?", Integer.class,
                    accepted.getLast().eventId()), "participant has not flushed");
        });
        owner.runInTransaction(() -> {
            observe();
            participant.required(); participant.mandatory();
            runner.runInTransaction(() -> participant.required());
            runner.runInTransaction(() -> accepted.add(publish(BINDING_FACT, UUID.randomUUID())));
            assertEquals(0, suffix.calls);
            runner.requireOpen();
        });
        assertEquals(1, suffix.calls);
        assertEquals(accepted, suffix.last.events());
        assertEquals(accepted, refs("association"));
        assertThrows(IllegalTransactionStateException.class, participant::mandatory);
    }

    @Test void familyOrderDeclarationOrderUuidOrderAndImmutableSnapshot() {
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), low = new UUID(0, 1);
        List<EventRef> accepted = new ArrayList<>();
        owner.runInTransaction(() -> {
            accepted.add(publish(USER_CREATED_FACT, high));
            accepted.add(publish(BUSINESS_EVENT, high));
            accepted.add(publish(BINDING_FACT, high));
            accepted.add(publish(BUSINESS_EVENT, low));
            accepted.add(publish(COMMAND_TERMINAL_EVENT, high));
            accepted.add(publish(BINDING_FACT, low));
            // The same UUID in different families is valid.
        });
        assertEquals(accepted, suffix.last.events());
        assertEquals(List.of(new EventRef(BUSINESS_EVENT, high), new EventRef(BUSINESS_EVENT, low)), suffix.last.inFamily(BUSINESS_EVENT));
        assertEquals(List.of(BUSINESS_EVENT.name(), COMMAND_TERMINAL_EVENT.name(), BINDING_FACT.name(), USER_CREATED_FACT.name()),
                jdbc.queryForList("select family from experiment_4b.e_access order by ord", String.class));
        assertEquals(SyntheticSuffix.ORDER.stream().flatMap(f -> accepted.stream().filter(e -> e.family() == f)).toList(), refs("association"));
        assertThrows(UnsupportedOperationException.class, () -> suffix.last.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> suffix.last.inFamily(BUSINESS_EVENT).clear());
        var closed = suffix.last;
        owner.runInTransaction(() -> {});
        assertEquals(accepted, closed.events(), "cleanup must not mutate a closed snapshot");
        assertThrows(IllegalTransactionStateException.class, () -> declarations.recordPublishedEvent(accepted.getFirst()));
    }

    @Test void emptyBatchCommitsWithAllSyntheticELockedElsewhereAndAcquiresNoELock() throws Exception {
        try (var held = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            held.setAutoCommit(false);
            held.createStatement().execute("lock table experiment_4b.e_access in access exclusive mode");
            owner.runInTransaction(() -> {
                jdbc.execute("set local lock_timeout='300ms'");
                observe();
                mark("empty-action");
                assertEquals(0, jdbc.queryForObject("select count(*) from pg_locks where pid=pg_backend_pid() and relation='experiment_4b.e_access'::regclass", Integer.class));
            });
            assertEquals(0, suffix.calls);
            assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED), outcomes);
            held.rollback();
        }
        assertEquals(0, count("experiment_4b.e_access"));
    }

    @Test void duplicateCaughtInsideParticipantIrreversiblyPoisonsOwner() {
        UUID id = UUID.randomUUID();
        assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); EventRef ref = publish(BUSINESS_EVENT, id);
            runner.runInTransaction(() -> assertThrows(IllegalTransactionStateException.class, () -> declarations.recordPublishedEvent(ref)));
            assertThrows(IllegalTransactionStateException.class, runner::requireOpen);
        }));
        assertEquals(0, independentCount(id)); assertEquals(0, suffix.calls); assertRolledBack();
    }
    @Test void participantActionFailureCaughtByCallerStillPoisons() {
        assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> runner.runInTransaction((Runnable) () -> { throw new IllegalStateException("participant"); }));
        }));
        assertEquals(0, suffix.calls); assertRolledBack();
    }
    @Test void nullDeclarationCaughtByCallerStillPoisons() {
        assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BINDING_FACT, UUID.randomUUID());
            assertThrows(IllegalTransactionStateException.class, () -> declarations.recordPublishedEvent(null));
        }));
        assertRolledBack();
    }
    @Test void unknownAmbientRefusedBeforePublicationAndCaughtGuardDoomsPhysicalTransaction() {
        var t = new TransactionTemplate(manager);
        assertThrows(UnexpectedRollbackException.class, () -> t.executeWithoutResult(s -> {
            observe(); mark("unknown-ambient");
            assertThrows(IllegalTransactionStateException.class, () -> runner.runInTransaction((Runnable) () -> fail("unknown action invoked")));
            assertThrows(IllegalTransactionStateException.class, () -> writer.accept(BINDING_FACT, UUID.randomUUID()));
            assertThrows(IllegalTransactionStateException.class, () -> declarations.recordPublishedEvent(new EventRef(BINDING_FACT, UUID.randomUUID())));
        }));
        assertRolledBack(); assertEquals(0, suffix.calls);
    }
    @Test void synchronizationOnlyAmbientIsRefusedAndNoBatchLeaks() {
        TransactionSynchronizationManager.initSynchronization();
        try { assertThrows(IllegalTransactionStateException.class, () -> runner.runInTransaction((Runnable) () -> fail("invoked"))); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void explicitRollbackOnlySkipsSuffixAndCannotReturnSuccess() {
        assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, UUID.randomUUID());
            EntityManagerFactoryUtils.getTransactionalEntityManager(emf).getTransaction().setRollbackOnly();
            return "APPLIED";
        }));
        assertEquals(0, suffix.calls); assertRolledBack();
    }
    @Test void actionFailurePropagatesUnchangedAfterConfirmedPhysicalRollback() {
        UUID id = UUID.randomUUID(); var expected = new IllegalStateException("action");
        assertSame(expected, assertThrows(IllegalStateException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, id); throw expected;
        })));
        assertEquals(0, independentCount(id)); assertEquals(0, suffix.calls); assertRolledBack();
    }
    @Test void deferredFlushPkCollisionIsCommitPhaseButPhysicallyRolledBackAndNeverMerge() {
        UUID id = UUID.randomUUID(); owner.runInTransaction(() -> publish(BUSINESS_EVENT, id));
        var original = jdbc.queryForMap("select * from business_event_outbox where id=?", id);
        jdbc.execute("truncate experiment_4b.association, experiment_4b.accepted, experiment_4b.e_access, experiment_4b.audit");
        suffix.calls = 0;
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, id); // Persist of a fresh object with an existing DB identity.
            assertEquals(0, suffix.calls);
        }));
        assertEquals(COMMIT, failure.phase()); assertEquals(ROLLED_BACK, failure.outcome());
        assertEquals(original, jdbc.queryForMap("select * from business_event_outbox where id=?", id));
        assertEquals(0, suffix.calls); assertRolledBack();
    }
    @Test void suffixAssociationErrorIsCommitPhaseWithConfirmedPhysicalRollback() {
        suffix.failAfterAssociation = true; UUID id = UUID.randomUUID();
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, id);
        }));
        assertEquals(COMMIT, failure.phase()); assertEquals(ROLLED_BACK, failure.outcome());
        assertEquals(1, suffix.calls); assertEquals(0, independentCount(id)); assertRolledBack();
    }
    @Test void latePublicationAndDeclarationCaughtInAdversarialSuffixStillRollbackEverything() {
        UUID id = UUID.randomUUID();
        var adversarial = new PrimaryEventTransactionRunner(manager, snapshot -> {
            assertThrows(IllegalTransactionStateException.class, () -> writer.accept(BINDING_FACT, UUID.randomUUID()));
            assertThrows(IllegalTransactionStateException.class, () -> declarations.recordPublishedEvent(new EventRef(BINDING_FACT, UUID.randomUUID())));
            assertThrows(IllegalTransactionStateException.class, () -> runner.runInTransaction((Runnable) () -> fail("late participant")));
        });
        var guarded = new ConsumptionOwnerTransactionRunner(adversarial, true);
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> guarded.runInTransaction(() -> {
            observe(); publish(BUSINESS_EVENT, id);
        }));
        assertEquals(COMMIT, failure.phase()); assertEquals(ROLLED_BACK, failure.outcome());
        assertEquals(0, independentCount(id)); assertRolledBack();
    }

    @Test void realConsumptionFinalizesSlotAndClaimBeforeDeferredFlushClosedSnapshotAndSuffix() {
        var claim = owner.runInTransaction(() -> lifecycle.acquire(
                new ConsumptionKey(new ConsumableIdentity("STEP4B", List.of(UUID.randomUUID().toString())), new ConsumerIdentity("STEP4B", List.of("1"))),
                ClaimId.generate(), new WorkerId("step4b"), new ClaimLease(Duration.ofSeconds(30)), Instant.now()));
        var acquired = assertInstanceOf(AcquireResult.Acquired.class, claim).claim();
        jdbc.execute("truncate experiment_4b.audit");
        UUID id = UUID.randomUUID();
        var service = new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(terminal.finalizer()), owner);
        service.execute(new ExecuteConsumptionInput(acquired.slotId(), acquired.claimId(), ignored -> {
            publish(BINDING_FACT, UUID.randomUUID());
            // The actual finalizer flushes before CAS S; an observation-free deferred JPA write is
            // tested separately. No real producer signature is changed to add declarations.
            publish(BUSINESS_EVENT, id);
            return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(), List.of());
        }));
        var phases = jdbc.queryForList("select phase from experiment_4b.audit order by ord", String.class);
        assertTrue(phases.indexOf("consumption_slots:UPDATE") < phases.indexOf("consumption_claims:UPDATE"), phases.toString());
        assertTrue(phases.indexOf("consumption_claims:UPDATE") < phases.indexOf("e_access:INSERT"), phases.toString());
        assertEquals("DONE", jdbc.queryForObject("select status from consumption_slots where slot_id=?", String.class, acquired.slotId()));
        assertNotNull(jdbc.queryForObject("select ended_at from consumption_claims where claim_id=?", Instant.class, acquired.claimId().value()));
        assertEquals(1, suffix.calls); assertEquals(1, independentCount(id));
        assertEquals(1, jdbc.queryForObject("select count(distinct(pid,xid)) from experiment_4b.audit", Integer.class));
        System.out.println("STEP4B CONSUMPTION " + jdbc.queryForList("select * from experiment_4b.audit order by ord"));
    }

    @Test void suffixFailurePhysicallyRestoresRealConsumptionSlotAndClaim() {
        var claim = assertInstanceOf(AcquireResult.Acquired.class, owner.runInTransaction(() -> lifecycle.acquire(
                new ConsumptionKey(new ConsumableIdentity("STEP4B", List.of(UUID.randomUUID().toString())),
                        new ConsumerIdentity("STEP4B", List.of("1"))), ClaimId.generate(), new WorkerId("step4b"),
                new ClaimLease(Duration.ofSeconds(30)), Instant.now()))).claim();
        jdbc.execute("truncate experiment_4b.audit");
        suffix.failAfterAssociation = true; UUID id = UUID.randomUUID();
        var service = new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(terminal.finalizer()), owner);
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> service.execute(
                new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), ignored -> {
                    observe(); publish(BUSINESS_EVENT, id);
                    return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(), List.of());
                })));
        assertEquals(COMMIT, failure.phase()); assertEquals(ROLLED_BACK, failure.outcome());
        assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots where slot_id=?", String.class, claim.slotId()));
        assertNull(jdbc.queryForObject("select ended_at from consumption_claims where claim_id=?", Instant.class, claim.claimId().value()));
        assertEquals(0, independentCount(id)); assertRolledBack();
    }
    @Test void successfulTerminalFlushOccursAfterActionReturnsAndBeforeClosedSuffix() {
        var statistics = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled(); statistics.setStatisticsEnabled(true);
        long[] flushesAtActionReturn = { -1 };
        var instrumented = new PrimaryEventTransactionRunner(manager, closed -> {
            assertTrue(statistics.getFlushCount() > flushesAtActionReturn[0], "terminal flush must follow the action");
            suffix.execute(closed);
        });
        try {
            var guarded = new ConsumptionOwnerTransactionRunner(instrumented, true);
            guarded.runInTransaction(() -> {
                publish(BUSINESS_EVENT, UUID.randomUUID());
                flushesAtActionReturn[0] = statistics.getFlushCount();
            });
            assertEquals(1, suffix.calls);
        } finally { statistics.setStatisticsEnabled(previous); }
    }
    @Test void cleanupFallbackWorksWhenSynchronizationRegistrationCannotBeInstalled() {
        var noSynchronization = new JpaTransactionManager(emf);
        noSynchronization.setDataSource(jdbc.getDataSource());
        noSynchronization.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        var protocol = new PrimaryEventTransactionRunner(noSynchronization, suffix);
        assertThrows(IllegalStateException.class, () -> protocol.runInTransaction((Runnable) () -> fail("installation failed")));
        assertClean(); assertEquals(0, suffix.calls);
        owner.runInTransaction(() -> publish(BINDING_FACT, UUID.randomUUID()));
        assertEquals(1, suffix.calls);
    }

    @Test void committedAfterCommitObserverFailureIsCommittedAndNeverAutomaticallyReplayed() {
        UUID id = UUID.randomUUID(); AtomicInteger attempts = new AtomicInteger();
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> owner.runInTransaction(() -> {
            attempts.incrementAndGet(); observe(); publish(BUSINESS_EVENT, id);
            runner.runAfterCommit(() -> { throw new IllegalStateException("observer"); });
        }));
        assertEquals(COMMITTED, failure.outcome()); assertEquals(COMMIT, failure.phase());
        assertEquals(1, attempts.get()); assertEquals(1, independentCount(id));
        assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED), outcomes);
    }

    /** Test-only boundary faults. No replacement manager is ever registered in the application. */
    static final class ResolutionFaultManager extends JpaTransactionManager {
        enum Fault { BEGIN, COMMIT_ACK, ROLLBACK_ACK }
        private final Fault fault;
        ResolutionFaultManager(EntityManagerFactory emf, javax.sql.DataSource ds, Fault fault) {
            super(emf); setDataSource(ds); this.fault = fault;
        }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {
            if (fault == Fault.BEGIN) throw new CannotCreateTransactionException("injected begin refusal");
            super.doBegin(tx, definition);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (fault == Fault.COMMIT_ACK) throw new TransactionSystemException("injected missing commit acknowledgement");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            super.doRollback(status);
            if (fault == Fault.ROLLBACK_ACK) throw new TransactionSystemException("injected missing rollback acknowledgement");
        }
    }
    @Test void beginRefusalHasUnknownResolutionNoActionNoBatchAndNextTransactionIsClean() {
        var faulty = new ResolutionFaultManager(emf, jdbc.getDataSource(), ResolutionFaultManager.Fault.BEGIN);
        var protocol = new PrimaryEventTransactionRunner(faulty, suffix);
        var guarded = new ConsumptionOwnerTransactionRunner(protocol, true);
        var failure = assertThrows(ConsumptionTransactionResolutionException.class,
                () -> guarded.runInTransaction((Runnable) () -> fail("begin failed; action must not run")));
        assertEquals(UNKNOWN, failure.outcome()); assertEquals(ROLLBACK, failure.phase());
        assertInstanceOf(CannotCreateTransactionException.class, failure.getCause());
        assertEquals(0, suffix.calls); assertClean();
        owner.runInTransaction(() -> publish(BINDING_FACT, UUID.randomUUID()));
        assertEquals(1, suffix.calls);
    }
    @Test void unknownCommitClassificationWithDurableRowsDoesNotReplayOrEraseResolution() {
        var faulty = new ResolutionFaultManager(emf, jdbc.getDataSource(), ResolutionFaultManager.Fault.COMMIT_ACK);
        var protocol = new PrimaryEventTransactionRunner(faulty, suffix);
        var syntheticWriter = new SyntheticWriter(protocol, jdbc);
        var guarded = new ConsumptionOwnerTransactionRunner(protocol, true);
        UUID id = UUID.randomUUID(); AtomicInteger attempts = new AtomicInteger();
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> guarded.runInTransaction(() -> {
            attempts.incrementAndGet(); observe();
            protocol.recordPublishedEvent(syntheticWriter.accept(BUSINESS_EVENT, id));
        }));
        assertEquals(UNKNOWN, failure.outcome()); assertEquals(COMMIT, failure.phase());
        assertEquals(List.of(TransactionSynchronization.STATUS_UNKNOWN), outcomes);
        assertEquals(1, independentCount(id)); assertEquals(1, attempts.get()); assertClean();
        // This is deterministic Java fault injection after a real commit, not a network outage proof.
        owner.runInTransaction(() -> {}); assertEquals(1, suffix.calls);
    }
    @Test void unknownRollbackClassificationPreservesActionCauseDespitePhysicalRollbackObservation() {
        var faulty = new ResolutionFaultManager(emf, jdbc.getDataSource(), ResolutionFaultManager.Fault.ROLLBACK_ACK);
        var protocol = new PrimaryEventTransactionRunner(faulty, suffix);
        var syntheticWriter = new SyntheticWriter(protocol, jdbc);
        var guarded = new ConsumptionOwnerTransactionRunner(protocol, true);
        UUID id = UUID.randomUUID(); var expected = new IllegalStateException("action before rollback fault");
        var failure = assertThrows(ConsumptionTransactionResolutionException.class, () -> guarded.runInTransaction(() -> {
            observe(); protocol.recordPublishedEvent(syntheticWriter.accept(BUSINESS_EVENT, id)); throw expected;
        }));
        assertEquals(UNKNOWN, failure.outcome()); assertEquals(ROLLBACK, failure.phase());
        assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(expected));
        assertEquals(List.of(TransactionSynchronization.STATUS_UNKNOWN), outcomes);
        assertEquals(0, independentCount(id)); assertEquals(0, suffix.calls);
        assertEquals(0, count("experiment_4b.accepted")); assertClean();
        owner.runInTransaction(() -> publish(BINDING_FACT, UUID.randomUUID()));
        assertEquals(1, suffix.calls);
    }

    @Test void mixedOutcomesOnOneReusedThreadHaveNoResourceOrSnapshotLeak() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            var snapshots = pool.submit(() -> {
                List<ClosedPublishedEvents> seen = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    assertClean(); UUID id = UUID.randomUUID();
                    if (i % 3 == 0) {
                        owner.runInTransaction(() -> publish(BUSINESS_EVENT, id));
                        seen.add(suffix.last); assertEquals(List.of(new EventRef(BUSINESS_EVENT, id)), suffix.last.events());
                    } else if (i % 3 == 1) {
                        assertThrows(IllegalStateException.class, () -> owner.runInTransaction(() -> {
                            publish(BINDING_FACT, id); throw new IllegalStateException("abort");
                        }));
                    } else { owner.runInTransaction(() -> {}); }
                    assertClean();
                }
                return seen;
            }).get(20, TimeUnit.SECONDS);
            assertNotSame(snapshots.getFirst(), snapshots.getLast());
            assertEquals(2, suffix.calls); assertEquals(2, count("experiment_4b.association"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
