package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;

import static com.kartaguez.pocoma.runtime.task.v3.SubobjectPostgresFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Testcontainers
class SubobjectAtomicityPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(IMAGE).withDatabaseName("i1_read");

    @BeforeAll static void init() { initialize(PG); }
    static void fault(String point) { throw new IllegalStateException("I1 injected "+point); }

    ConsumptionParticipant custom(SubobjectPostgresFixture f,Consumer<SlotCompletion> finalizer,Runnable readiness) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                return reads.effects(
                        preparationContext -> {
                            return Optional.of(
                                    new Prepared() {
                                        public void finalizeIn(SlotCompletion completion) {
                                            finalizer.accept(completion);
                                        }

                                        public void requireCompletionReady() {
                                            readiness.run();
                                        }
                                    });
                        });
            }

            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                return c -> evidence(observeInSnapshot(id, c, reads));
            }

            private RecoveryObservation observeInSnapshot(
                    UUID id, RecoveryReadContext c, PreparationReads reads) {
                return legacy(f.participant().prepareRecovery(id, reads).observe(c));
            }
        };
    }
    void rolledBack(SubobjectPostgresFixture f,UUID id,ConsumptionProtocol.Attempt attempt,int prior) {
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK,attempt.status(),()->String.valueOf(attempt));
        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,attempt.resolution());
        assertEquals(prior,f.count()); assertEquals(1,f.slotCount(id));
        assertEquals(
                f.acquiredRows.get(id),
                f.oracle.queryForMap("select * from consumption_slot where consumable_id=?", id));
        assertEquals(
                0, f.oracle.queryForObject("select count(*) from fixture_witness", Integer.class));
        assertEquals(
                0,
                f.oracle.queryForObject(
                        "select attempt_count from consumption_slot where consumable_id=?",
                        Integer.class,
                        id));
        f.trace.add("ROLLED_BACK="+attempt+" snapshot="+f.snapshot(id));
    }
    @Test void effectsAndSlotSameUnit() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"effectsAndSlotSameUnit")) {
            f.installWitnesses();var values=eight(UUID.randomUUID());var id=f.seed(values);
            var atDelete=new CountDownLatch(1);var release=new CountDownLatch(1);var owner=new AtomicReference<Map<String,Object>>();
            var engine =
                    f.engine(
                            f.participant(
                                    (point, key, c) -> {
                                        if (point == Point.AFTER_DELETE) {
                                            owner.set(
                                                    f.witness(
                                                            f.source,
                                                            "after-delete before commit"));
                                            atDelete.countDown();
                                            await(release);
                                        }
                                    }));
            var handle=f.acquire(engine,id);var task=f.threads.submit(()->engine.execute(handle));
            try {
                await(atDelete);
                assertEquals(Map.of("effects", 0L, "slots", 1L, "witnesses", 0L), f.snapshot(id));
                // Lock remains held until physical resolution, including after the local DELETE.
                var contender =
                        f.threads.submit(
                                () -> {
                                    var ds = f.datasource(f.adminRole);
                                    try (var connection = ds.getConnection()) {
                                        connection.setAutoCommit(false);
                                        try (var lock = connection.createStatement()) {
                                            lock.execute("set local lock_timeout='250ms'");
                                            var pid = lock.executeQuery("select pg_backend_pid()");
                                            pid.next();
                                            f.trace.add("slot lock contender PID=" + pid.getInt(1));
                                            var update =
                                                    connection.prepareStatement(
                                                            "select consumable_id from"
                                                                + " consumption_slot where"
                                                                + " consumable_id=? for update");
                                            update.setObject(1, id);
                                            var failure =
                                                    assertThrows(
                                                            java.sql.SQLException.class,
                                                            update::executeQuery);
                                            assertEquals("55P03", failure.getSQLState());
                                            f.trace.add(
                                                    "slot retained lock SQLSTATE="
                                                            + failure.getSQLState());
                                        } finally {
                                            connection.rollback();
                                        }
                                    }
                                    return true;
                                });
                assertTrue(get(contender));
            } finally {release.countDown();}
            var attempt=get(task);assertEquals(ConsumptionProtocol.Status.COMMITTED,attempt.status());
            assertEquals(Map.of("effects",8L,"slots",0L,"witnesses",9L),f.snapshot(id));
            assertEquals(new HashSet<>(values),new HashSet<>(f.read(values).values()));f.assertWitnesses(owner.get(),8,1);
            assertTrue(
                    f.trace.stream()
                            .anyMatch(s -> s.startsWith("readiness ") && s.endsWith("slots=1")));
        }
    }
    @Test void rollbackAfterDeleteAndEveryBoundary() throws Exception {
        for(var boundary:List.of(Point.BEFORE_INSERT,Point.AFTER_INSERT,Point.READINESS,Point.BEFORE_DELETE,Point.AFTER_DELETE,Point.AFTER_CLOSE)) {
            try(var f=new SubobjectPostgresFixture(PG,"rollback-"+boundary)) {
                var prior=header(UUID.randomUUID(),1,false);f.insert(List.of(prior));f.installWitnesses();
                var id=f.seed(eight(UUID.randomUUID()));var owner=new AtomicReference<Map<String,Object>>();
                var engine =
                        f.engine(
                                f.participant(
                                        (point, key, c) -> {
                                            if (point == boundary) {
                                                owner.set(
                                                        f.witness(
                                                                f.source,
                                                                "rollback boundary=" + point));
                                                fault(point.name());
                                            }
                                        }));
                var result=engine.execute(f.acquire(engine,id));rolledBack(f,id,result,1);
                assertEquals("I1 injected "+boundary.name(),result.cause().getMessage());
                assertNotNull(owner.get());assertEquals(prior,f.read(List.of(prior)).get(prior.key()));
            }
        }
    }
    @Test void rollbackAfterFirstChunkAndBeforeCommit() throws Exception {
        for(boolean firstChunk:List.of(true,false)) {
            try (var f =
                    new SubobjectPostgresFixture(
                            PG, firstChunk ? "rollbackFirstChunk" : "rollbackBeforeCommit")) {
                f.installWitnesses();var id=f.seed(f.batch(UUID.randomUUID(),600));var count=new AtomicInteger();
                if (firstChunk)
                    f.source.after =
                            statement -> {
                                if (statement.startsWith("insert into read_subobject_version")
                                        && count.incrementAndGet() == 1) {
                                    assertEquals(
                                            256,
                                            f.sql.queryForObject(
                                                    "select count(*) from read_subobject_version",
                                                    Integer.class));
                                    f.witness(f.source, "fault after first 256 physical inserts");
                                    fault("first chunk");
                                }
                            };
                var engine =
                        f.engine(
                                f.participant(
                                        (point, key, c) -> {
                                            if (!firstChunk && point == Point.AFTER_CLOSE)
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    public void beforeCommit(
                                                                            boolean readOnly) {
                                                                        f.witness(
                                                                                f.source,
                                                                                "before physical"
                                                                                    + " commit");
                                                                        fault("before commit");
                                                                    }
                                                                });
                                        }));
                var result=engine.execute(f.acquire(engine,id));
                if(firstChunk) {
                    rolledBack(f, id, result, 0);
                    assertEquals(1, count.get());
                    assertEquals("I1 injected first chunk", result.cause().getMessage());
                } else {
                    // Existing L2 conservatively retains UNKNOWN for a callback in Spring's commit phase.
                    assertEquals(AttemptTransactions.Resolution.UNKNOWN,result.resolution());
                    assertEquals(ConsumptionProtocol.Status.RECOVERY_PENDING,result.status());
                    assertEquals(RecoveryObservation.NOT_COMPLETED,result.observation());
                    assertEquals("I1 injected before commit",result.cause().getMessage());
                    assertEquals(Map.of("effects",0L,"slots",1L,"witnesses",0L),f.snapshot(id));
                    assertEquals(1, f.capacity.snapshot().worker());
                    f.trace.add("before-commit UNKNOWN retained=" + result);
                }
            }
        }
    }
    @Test void faultBetweenReadinessAndDelete() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"afterReadinessBeforeDelete")) {
            f.installWitnesses();var id=f.seed(eight(UUID.randomUUID()));var reached=new AtomicBoolean();
            f.source.before =
                    statement -> {
                        if (statement.startsWith("delete from consumption_slot")) {
                            assertTrue(f.trace.stream().anyMatch(s -> s.startsWith("readiness ")));
                            reached.set(true);
                            f.witness(f.source, "after successful readiness before DELETE SQL");
                            fault("before DELETE SQL");
                        }
                    };
            var engine=f.engine(f.participant());var result=engine.execute(f.acquire(engine,id));
            rolledBack(f, id, result, 0);
            assertTrue(reached.get());
            assertEquals("I1 injected before DELETE SQL", result.cause().getMessage());
        }
    }
    @Test void actualReadiness() throws Exception {
        for(boolean missing:List.of(true,false)) {
            try(var f=new SubobjectPostgresFixture(PG,"readiness-"+missing)) {
                f.installWitnesses();var values=eight(UUID.randomUUID());var id=f.seed(values);var readinessFailure=new AtomicReference<RuntimeException>();
                var engine =
                        f.engine(
                                custom(
                                        f,
                                        c -> {
                                            f.store.insertOrVerifyAll(
                                                    missing ? values.subList(0, 7) : values);
                                            try {
                                                c.complete();
                                            } catch (RuntimeException intercepted) {
                                                readinessFailure.set(intercepted);
                                                f.trace.add(
                                                        "caught readiness="
                                                                + intercepted.getClass());
                                            }
                                            SpringAttemptTransactions.closePublication(f.source);
                                        },
                                        () -> {
                                            if (missing) f.store.requireStored(values);
                                            else
                                                f.store.requireStored(
                                                        List.of(
                                                                header(
                                                                        values.getFirst()
                                                                                .key()
                                                                                .potId(),
                                                                        1,
                                                                        true)));
                                        }));
                var result=engine.execute(f.acquire(engine,id));rolledBack(f,id,result,0);
                if(missing) assertInstanceOf(CorruptSubobjectException.class,readinessFailure.get());
                else assertInstanceOf(SubobjectConflictException.class,readinessFailure.get());
                assertSame(readinessFailure.get(), result.cause());
            }
        }
    }
    @Test void caughtFailurePoisons() throws Exception {
        for(String type:List.of("divergence","duplicate","null","sql")) {
            try(var f=new SubobjectPostgresFixture(PG,"caught-"+type)) {
                var original=header(UUID.randomUUID(),1,false);f.insert(List.of(original));f.installWitnesses();
                if(type.equals("sql")) {
                    f.oracle.execute(
                            "create function fixture_fail() returns trigger language plpgsql as $$"
                                    + " begin raise exception 'fixture SQL rejection' using"
                                    + " errcode='23514';end $$");
                    f.oracle.execute(
                            "create trigger fixture_fail before insert on read_subobject_version"
                                    + " for each row execute function fixture_fail()");
                }
                var values=eight(UUID.randomUUID());var id=f.seed(values);var caught=new AtomicBoolean();var writerFailure=new AtomicReference<RuntimeException>();
                var engine =
                        f.engine(
                                custom(
                                        f,
                                        c -> {
                                            if (!type.equals("sql"))
                                                f.store.insertOrVerifyAll(values);
                                            try {
                                                switch (type) {
                                                    case "divergence" ->
                                                            f.store.insertOrVerifyAll(
                                                                    List.of(
                                                                            header(
                                                                                    original.key()
                                                                                            .potId(),
                                                                                    1,
                                                                                    true)));
                                                    case "duplicate" ->
                                                            f.store.insertOrVerifyAll(
                                                                    List.of(
                                                                            values.getFirst(),
                                                                            values.getFirst()));
                                                    case "null" -> f.store.insertOrVerifyAll(null);
                                                    case "sql" -> f.store.insertOrVerifyAll(values);
                                                }
                                            } catch (RuntimeException intercepted) {
                                                caught.set(true);
                                                writerFailure.set(intercepted);
                                                f.trace.add(
                                                        "caught writer=" + intercepted.getClass());
                                            }
                                            try {
                                                c.complete();
                                            } catch (RuntimeException intercepted) {
                                            }
                                            try {
                                                SpringAttemptTransactions.closePublication(
                                                        f.source);
                                            } catch (RuntimeException intercepted) {
                                            }
                                        },
                                        () -> f.store.requireStored(values)));
                rolledBack(f,id,engine.execute(f.acquire(engine,id)),1);assertTrue(caught.get());
                switch(type) {
                    case "divergence" ->
                            assertInstanceOf(SubobjectConflictException.class, writerFailure.get());
                    case "duplicate" ->
                            assertInstanceOf(IllegalArgumentException.class, writerFailure.get());
                    case "null"->assertInstanceOf(NullPointerException.class,writerFailure.get());
                    case "sql" ->
                            assertEquals(
                                    "23514",
                                    SubobjectMigrationPostgresTest.state(writerFailure.get()));
                }
                assertEquals(original,f.read(List.of(original)).get(original.key()));
            }
        }
    }
    @Test void publicationClosure() throws Exception {
        for (String variant :
                List.of(
                        "early",
                        "missing",
                        "lateWriter",
                        "doubleComplete",
                        "missingComplete",
                        "foreignThread")) {
            try(var f=new SubobjectPostgresFixture(PG,"publication-"+variant)) {
                f.installWitnesses();var values=eight(UUID.randomUUID());var id=f.seed(values);var inserted=new AtomicBoolean();var ready=new AtomicBoolean();
                var engine =
                        f.engine(
                                custom(
                                        f,
                                        c -> {
                                            f.store.insertOrVerifyAll(values);
                                            inserted.set(true);
                                            if (variant.equals("early"))
                                                SpringAttemptTransactions.closePublication(
                                                        f.source);
                                            if (variant.equals("foreignThread")) {
                                                try {
                                                    get(
                                                            f.threads.submit(
                                                                    () -> {
                                                                        c.complete();
                                                                        return null;
                                                                    }));
                                                } catch (Exception intercepted) {
                                                    f.trace.add(
                                                            "foreign completion refused="
                                                                    + intercepted.getClass());
                                                }
                                            } else if (!variant.equals("missingComplete")) {
                                                try {
                                                    c.complete();
                                                } catch (RuntimeException intercepted) {
                                                }
                                            }
                                            if (variant.equals("doubleComplete"))
                                                try {
                                                    c.complete();
                                                } catch (RuntimeException intercepted) {
                                                }
                                            if (!variant.equals("early")
                                                    && !variant.equals("missing"))
                                                try {
                                                    SpringAttemptTransactions.closePublication(
                                                            f.source);
                                                } catch (RuntimeException intercepted) {
                                                }
                                            if (variant.equals("lateWriter"))
                                                try {
                                                    f.store.insertOrVerifyAll(List.of());
                                                } catch (RuntimeException intercepted) {
                                                }
                                        },
                                        () -> {
                                            f.store.requireStored(values);
                                            ready.set(true);
                                        }));
                rolledBack(f,id,engine.execute(f.acquire(engine,id)),0);
                assertTrue(inserted.get());
                assertEquals(
                        Set.of("missing", "lateWriter", "doubleComplete").contains(variant),
                        ready.get());
            }
        }
        try(var f=new SubobjectPostgresFixture(PG,"expiredCompletion")) {
            f.installWitnesses();var id=f.seed(eight(UUID.randomUUID()));var captured=new AtomicReference<SlotCompletion>();
            var engine=f.engine(f.participant((point,key,c)->{if(point==Point.AFTER_DELETE)captured.set(c);}));
            assertEquals(ConsumptionProtocol.Status.COMMITTED,engine.execute(f.acquire(engine,id)).status());
            assertThrows(IllegalStateException.class,()->captured.get().complete());assertEquals(8,f.count());assertEquals(0,f.slotCount(id));
        }
    }
    @Test void deferredCommitRejection() throws Exception {
        try(var f=new SubobjectPostgresFixture(PG,"deferredCommitRejection")) {
            f.installWitnesses();var id=f.seed(eight(UUID.randomUUID()));
            f.oracle.execute(
                    "create table fixture_parent(id int primary key);create table"
                        + " fixture_deferred(id int primary key,parent int references"
                        + " fixture_parent(id) deferrable initially deferred);grant select,insert"
                        + " on fixture_deferred to "
                            + f.runtimeRole);
            var reached=new AtomicBoolean();
            var engine =
                    f.engine(
                            f.participant(
                                    (point, key, c) -> {
                                        if (point == Point.AFTER_DELETE) {
                                            f.sql.update(
                                                    "insert into fixture_deferred values(1,99)");
                                            reached.set(true);
                                            f.witness(
                                                    f.source,
                                                    "after DELETE and deferred violation before"
                                                            + " commit");
                                        }
                                    }));
            var attempt=engine.execute(f.acquire(engine,id));rolledBack(f,id,attempt,0);assertTrue(reached.get());
            assertEquals("23503", SubobjectMigrationPostgresTest.state(attempt.cause()));
            assertEquals(
                    0,
                    f.oracle.queryForObject(
                            "select count(*) from fixture_deferred", Integer.class));
        }
    }
    @Test void physicalOracleRejectsIndependentTransactions() throws Exception {
        for(boolean rollback:List.of(true,false)) {
            try(var f=new SubobjectPostgresFixture(PG,"counterOracle-independent-"+rollback)) {
                f.installWitnesses();var value=header(UUID.randomUUID(),1,false);var id=f.seed(List.of(value));var owner=new AtomicReference<Map<String,Object>>();
                var engine =
                        f.engine(
                                custom(
                                        f,
                                        c -> {
                                            owner.set(f.witness(f.source, "counterexample owner"));
                                            // Deliberate test-only violation: no use of the
                                            // production guarded store.
                                            f.oracle.update(
                                                    "insert into read_subobject_version values"
                                                        + " (?,'POT_HEADER',?,1,1,'{\"deleted\":false}')",
                                                    value.key().potId(),
                                                    value.key().objectId());
                                            c.complete();
                                            SpringAttemptTransactions.closePublication(f.source);
                                            if (rollback) fault("independent orphan");
                                        },
                                        () -> f.store.requireStored(List.of(value))));
                var attempt=engine.execute(f.acquire(engine,id));assertEquals(rollback ? ConsumptionProtocol.Status.ROLLED_BACK : ConsumptionProtocol.Status.COMMITTED,attempt.status());
                assertEquals(1,f.count());assertEquals(rollback ? 1 : 0,f.slotCount(id));
                assertThrows(AssertionError.class,()->f.assertWitnesses(owner.get(),1,rollback ? 0 : 1));
                f.trace.add("COUNTER-ORACLE REJECTED two physical units; orphan="+rollback);
            }
        }
    }
    @Test void concurrentIdenticalAndDivergentSlots() throws Exception {
        for(boolean divergent:List.of(false,true)) {
            try(var f=new SubobjectPostgresFixture(PG,"atomicRace-"+divergent)) {
                f.installWitnesses();var pot=UUID.randomUUID();var first=f.seed(List.of(header(pot,1,false)));var second=f.seed(List.of(header(pot,1,divergent)));
                var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var loserPid=new AtomicInteger();
                var engine =
                        f.engine(
                                f.participant(
                                        (point, key, c) -> {
                                            if (key.equals(first) && point == Point.AFTER_INSERT) {
                                                inserted.countDown();
                                                await(release);
                                            }
                                            if (key.equals(second)
                                                    && point == Point.BEFORE_INSERT) {
                                                loserPid.set(
                                                        ((Number)
                                                                        f.witness(
                                                                                        f.source,
                                                                                        "second"
                                                                                            + " slot")
                                                                                .get("pid"))
                                                                .intValue());
                                                started.countDown();
                                            }
                                        }));
                var a=f.acquire(engine,first);var b=f.acquire(engine,second);var t1=f.threads.submit(()->engine.execute(a));await(inserted);
                var t2=f.threads.submit(()->engine.execute(b));
                try{await(started);f.waitForLock(loserPid.get());}finally{release.countDown();}
                assertEquals(ConsumptionProtocol.Status.COMMITTED,get(t1).status());
                var result=get(t2);assertEquals(divergent ? ConsumptionProtocol.Status.ROLLED_BACK : ConsumptionProtocol.Status.COMMITTED,result.status());
                assertEquals(1,f.count());assertEquals(0,f.slotCount(first));assertEquals(divergent ? 1 : 0,f.slotCount(second));
                assertEquals(
                        "false",
                        f.oracle.queryForObject(
                                "select payload->>'deleted' from read_subobject_version",
                                String.class));
                assertEquals(
                        divergent ? 1 : 2,
                        f.oracle.queryForObject(
                                "select count(*) from fixture_witness where op='DELETE'",
                                Integer.class));
                f.trace.add(
                        "race result="
                                + result
                                + " witnesses="
                                + f.oracle.queryForList(
                                        "select * from fixture_witness order by op,key"));
            }
        }
    }
    @Test void lateCaughtCallbackCannotCommit() throws Exception {
        for(boolean completionPhase:List.of(false,true)) for(boolean intercept:List.of(false,true)) {
                try (var f =
                        new SubobjectPostgresFixture(
                                PG,
                                "lateCallback-completion-"
                                        + completionPhase
                                        + "-caught-"
                                        + intercept)) {
                f.installWitnesses();var id=f.seed(eight(UUID.randomUUID()));var reached=new AtomicBoolean();
                    var engine =
                            f.engine(
                                    f.participant(
                                            (point, key, c) -> {
                                                if (point == Point.AFTER_CLOSE)
                                                    TransactionSynchronizationManager
                                                            .registerSynchronization(
                                                                    new TransactionSynchronization() {
                                                                        void lateWrite() {
                                                                            try {
                                                                                f.store
                                                                                        .insertOrVerifyAll(
                                                                                                List
                                                                                                        .of());
                                                                            } catch (
                                                                                    RuntimeException
                                                                                            failure) {
                                                                                reached.set(true);
                                                                                f.trace.add(
                                                                                        "late store"
                                                                                            + " refused="
                                                                                                + failure
                                                                                                        .getClass()
                                                                                                + " intercepted="
                                                                                                + intercept);
                                                                                if (!intercept)
                                                                                    throw failure;
                                                                            }
                                                                        }

                                                                        public void beforeCommit(
                                                                                boolean readOnly) {
                                                                            if (!completionPhase)
                                                                                lateWrite();
                                                                        }

                                                                        public void
                                                                                beforeCompletion() {
                                                                            if (completionPhase)
                                                                                lateWrite();
                                                                        }
                                                                    });
                                            }));
                    var result = engine.execute(f.acquire(engine, id));
                    f.trace.add("late callback result=" + result + " physical=" + f.snapshot(id));
                    assertTrue(reached.get());
                    assertEquals(0, f.count(), "poisoned prefix must not commit");
                    assertEquals(1, f.slotCount(id));
                    assertEquals(
                            f.acquiredRows.get(id),
                            f.oracle.queryForMap(
                                    "select * from consumption_slot where consumable_id=?", id));
                    assertEquals(
                            0,
                            f.oracle.queryForObject(
                                    "select count(*) from fixture_witness", Integer.class));
                assertNotEquals(AttemptTransactions.Resolution.COMMITTED,result.resolution());
            }
        }
    }
    @Test void lostCommitAck() throws Exception {
        for(var fault:List.of(CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            for(boolean unavailable:List.of(false,true)) {
                try(var f=new SubobjectPostgresFixture(PG,"ack-"+fault+"-unavailable-"+unavailable);
                    var proxy=new CommitAcknowledgementProxy(PG.getHost(),PG.getMappedPort(5432))) {
                    f.installWitnesses();var values=eight(UUID.randomUUID());var id=f.seed(values);
                    var ds =
                            new DriverManagerDataSource(
                                    "jdbc:postgresql://127.0.0.1:"
                                            + proxy.localPort()
                                            + "/"
                                            + PG.getDatabaseName()
                                            + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5&currentSchema="
                                            + f.schema,
                                    f.runtimeRole,
                                    "i1-fixture");
                    var tx=owner(ds);var storage=new JdbcSubobjectVersionStore(ds,tx);var witness=new AtomicReference<Map<String,Object>>();
                    var participant =
                            f.participant(
                                    ds,
                                    storage,
                                    (point, key, c) -> {
                                        if (point == Point.AFTER_CLOSE) {
                                            witness.set(f.witness(ds, "armed " + fault));
                                            proxy.arm(fault);
                                        }
                                    },
                                    unavailable);
                    var engine =
                            new ConsumptionEngine(
                                    f.definition,
                                    tx,
                                    new JdbcConsumptionSlotStore(ds),
                                    participant,
                                    f.tx,
                                    Optional.of(f.references()),
                                    r -> true,
                                    new JdbcDiscoveryAdmissionStore(ds, discoverySettings()),
                                    tx,
                                    new JdbcConsumptionSlotStore(ds),
                                    new JdbcPartitionOwnershipStore(ds),
                                    f.settings,
                                    f.capacity,
                                    failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                                    null,
                                    ConsumptionObservation.noop());
                    var attempt=engine.execute(f.acquire(engine,id));
                    assertEquals(1,proxy.interceptedCount());assertEquals(fault==CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0,proxy.committedReplyCount());
                    assertEquals(AttemptTransactions.Resolution.UNKNOWN,attempt.resolution());assertNull(attempt.cession());assertNull(attempt.cessionResolution());
                    assertEquals(1,f.capacity.snapshot().worker());
                    boolean committed=fault==CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
                    assertEquals(unavailable ? RecoveryObservation.UNAVAILABLE : committed ? RecoveryObservation.COMPLETED : RecoveryObservation.NOT_COMPLETED,attempt.observation());
                    assertEquals(committed && !unavailable ? ConsumptionProtocol.Status.COMMITTED : ConsumptionProtocol.Status.RECOVERY_PENDING,attempt.status());
                    assertEquals(committed ? 8 : 0,f.count());assertEquals(committed ? 0 : 1,f.slotCount(id));
                    if (committed) f.assertWitnesses(witness.get(), 8, 1);
                    else
                        assertEquals(
                                0,
                                f.oracle.queryForObject(
                                        "select count(*) from fixture_witness", Integer.class));
                    f.trace.add(
                            "wire fault="
                                    + fault
                                    + " intercepted="
                                    + proxy.interceptedCount()
                                    + " server COMMIT replies="
                                    + proxy.committedReplyCount()
                                    + " attempt="
                                    + attempt
                                    + " capacity="
                                    + f.capacity.snapshot()
                                    + " snapshot="
                                    + f.snapshot(id));
                }
            }
    }

    private static com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
            evidence(RecoveryObservation value) {
        return switch (value) {
            case COMPLETED ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .SATISFIED;
            case NOT_COMPLETED ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .MISSING;
            case INCONSISTENT ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .DIVERGENT;
            case UNAVAILABLE ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .UNAVAILABLE;
        };
    }

    private static RecoveryObservation legacy(
            com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                    value) {
        return switch (value) {
            case SATISFIED -> RecoveryObservation.COMPLETED;
            case MISSING -> RecoveryObservation.NOT_COMPLETED;
            case DIVERGENT -> RecoveryObservation.INCONSISTENT;
            case UNAVAILABLE -> RecoveryObservation.UNAVAILABLE;
        };
    }
}
