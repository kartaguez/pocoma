package com.kartaguez.pocoma.runtime.task;

import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Status;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.engine.materialize.results.MaterializeCommandResult;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCommandTerminalResultStore;
import com.kartaguez.pocoma.infra.postgres.source.JdbcCommandTerminalSource;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.kartaguez.pocoma.runtime.task.SubobjectPostgresFixture.await;
import static com.kartaguez.pocoma.runtime.task.SubobjectPostgresFixture.get;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CommandResultAvailabilityPostgresTest {
    @Container
    static final PostgreSQLContainer PG = ReadParticipantsContainer.create();
    private static final Name RESULT = Name.COMMAND_RESULT;

    @BeforeAll
    static void initialize() {
        SubobjectPostgresFixture.initialize(PG);
    }

    @Test
    void resultPublicationAndSlotShareOneTransactionAndRetryPreservesIdentity() throws Exception {
        try (var f = pilot("atomic-idempotent")) {
            f.enroll();
            f.witnesses();
            for (String table : List.of("read_availability_counter", "read_availability_event")) {
                f.f.read.oracle.execute("create trigger c3_witness after insert or update on "
                        + table + " for each row execute function l4_witness()");
            }

            var engine = f.engines.get(RESULT);
            var sourceEvent = f.event(RESULT);
            var claim = f.acquire(RESULT, engine, sourceEvent);
            assertEquals(Status.COMMITTED, engine.execute(claim).status());
            assertEquals(RecoveryObservation.COMPLETED, engine.observe(claim.claim()));
            assertPrefix(f, 1);
            assertEquals(0, f.slots(RESULT, sourceEvent));
            var published = f.f.read.oracle.queryForMap("select * from read_availability_event");
            assertEquals(f.pot.request(), published.get("projection_key"));
            assertNotEquals(sourceEvent, published.get("event_id"));
            assertNotEquals(f.pot.request(), published.get("event_id"));
            assertEquals(
                    List.of("consumption_slot", "read_availability_counter",
                            "read_availability_event", "read_command_result"),
                    f.f.read.oracle.queryForList("select t from l4_witness order by t", String.class));
            assertEquals(1, f.f.read.oracle.queryForObject(
                    "select count(distinct (pid,xid)) from l4_witness", Integer.class));

            // A synthetic repeated slot exercises the participant's idempotence directly.
            // Canonical Discovery does not normally re-enroll a covered completion event.
            var definition = f.participants.get(RESULT).definition();
            f.f.read.oracle.update("""
                    insert into consumption_slot (consumption_id,consumable_id,partition_hash,partition_id)
                    select ?,?,h,((h::bigint%4+4)%4)::int from (select consumption_hash_v1(?,?) h) x
                    """, definition.id().value(), sourceEvent, definition.family().name(), sourceEvent);
            assertEquals(Status.COMMITTED, engine.execute(f.acquire(RESULT, engine, sourceEvent)).status());
            assertEquals(0, f.slots(RESULT, sourceEvent));
            assertPrefix(f, 1);
            assertEquals(published, f.f.read.oracle.queryForMap("select * from read_availability_event"));
            f.f.read.trace.add("Same backend/xid for result, slot DELETE, counter and event; retry preserved the envelope");
        }
    }

    @Test
    void missingSuffixCannotCommitResultOrDeleteSlot() throws Exception {
        try (var f = pilot("missing-suffix")) {
            var db = f.f.read;
            var store = store(db.source, db.tx);
            var legacyClosure = new MaterializeCommandResult(
                    new JdbcCommandTerminalSource(f.f.primary, f.f.sourceOwner),
                    new JdbcCommandTerminalResultStore(db.source, db.tx));
            var engine = f.engine(RESULT, legacyClosure, db.source, db.tx);
            f.enroll();
            var claim = f.acquire(RESULT, engine, f.event(RESULT));
            var attempt = engine.execute(claim);
            assertEquals(Resolution.ROLLED_BACK, attempt.resolution());
            assertEquals("23514", sqlState(attempt.cause()));
            assertPrefix(f, 0);
            assertEquals(1, f.slots(RESULT, f.event(RESULT)));
            assertEquals(ReadEvidence.MISSING, db.tx.read(context -> store.evidence(expected(f), context)));
            f.f.read.trace.add("Deferred reverse constraint rejected a slot DELETE with omitted publication");
        }
    }

    @Test
    void caughtSqlFailureAfterAllocationStillRollsBackTheSlotAndPosition() throws Exception {
        try (var f = pilot("caught-suffix-failure")) {
            var db = f.f.read;
            var actual = participant(f, db.source, db.tx);
            var caught = new AtomicBoolean();
            var wrapper = new ConsumptionParticipant() {
                @Override
                public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
                    return actual.prepare(event, reads).map(prepared -> new Prepared() {
                        @Override
                        public void requireCompletionReady() {
                            prepared.requireCompletionReady();
                        }

                        @Override
                        public void finalizeIn(SlotCompletion completion) {
                            try {
                                prepared.finalizeIn(completion);
                            } catch (RuntimeException failure) {
                                assertEquals("23514", sqlState(failure));
                                caught.set(true);
                            }
                        }
                    });
                }

                @Override
                public Recovery prepareRecovery(UUID event, PreparationReads reads) {
                    return actual.prepareRecovery(event, reads);
                }
            };
            db.oracle.execute("""
                    create function c3_reject_event() returns trigger language plpgsql as $$
                    begin
                      raise exception 'Injected after counter allocation' using errcode = '23514';
                    end $$
                    """);
            db.oracle.execute("""
                    create trigger c3_reject_event before insert on read_availability_event
                    for each row execute function c3_reject_event()
                    """);
            f.enroll();
            var engine = f.engine(RESULT, wrapper, db.source, db.tx);
            var event = f.event(RESULT);
            assertEquals(Resolution.ROLLED_BACK, engine.execute(f.acquire(RESULT, engine, event)).resolution());
            assertTrue(caught.get());
            assertPrefix(f, 0);
            assertEquals(1, f.slots(RESULT, event));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAllocationWaitsForCommitOrReusesRolledBackPosition(boolean rollbackFirst)
            throws Exception {
        try (var f = pilot("counter-concurrency-" + rollbackFirst)) {
            var second = f.f.publish();
            f.enroll();
            var db = f.f.read;
            var engine = f.engines.get(RESULT);
            var firstClaim = f.acquire(RESULT, engine, f.event(RESULT));
            var secondClaim = f.acquire(RESULT, engine, second.completion());
            var reserved = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var firstAllocation = new AtomicBoolean(true);
            db.source.after = sql -> {
                if (!sql.contains("update read_availability_counter")
                        || !firstAllocation.compareAndSet(true, false)) return;
                reserved.countDown();
                await(release);
                if (rollbackFirst) throw new IllegalStateException("Injected after counter allocation");
            };

            var first = db.threads.submit(() -> engine.execute(firstClaim));
            try {
                await(reserved);
                var next = db.threads.submit(() -> engine.execute(secondClaim));
                awaitSqlLock(db, "update read_availability_counter");
                assertPrefix(f, 0);
                assertEquals(1, f.slots(RESULT, f.event(RESULT)));
                assertEquals(1, f.slots(RESULT, second.completion()));
                release.countDown();
                assertEquals(rollbackFirst ? Resolution.ROLLED_BACK : Resolution.COMMITTED, get(first).resolution());
                assertEquals(Resolution.COMMITTED, get(next).resolution());
            } finally {
                release.countDown();
                db.source.after = sql -> {};
            }

            assertPrefix(f, rollbackFirst ? 1 : 2);
            assertEquals(rollbackFirst ? 1 : 0, f.slots(RESULT, f.event(RESULT)));
            assertEquals(0, f.slots(RESULT, second.completion()));
            assertEquals(rollbackFirst ? 1L : 2L, db.oracle.queryForObject(
                    "select position from read_availability_event where projection_key = ?",
                    Long.class, second.request()));
            f.f.read.trace.add("Counter wait observed on PostgreSQL, rollbackFirst=" + rollbackFirst);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CommitAcknowledgementProxy.Fault.class, names = {"BEFORE_COMMIT", "AFTER_COMMIT"})
    void commitUncertaintyIsRecoveredFromResultEventSlotAndCheckpoint(
            CommitAcknowledgementProxy.Fault fault) throws Exception {
        try (var f = pilot("ack-" + fault);
                var proxy = new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
            f.enroll();
            var db = f.f.read;
            var endpoint = new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:" + proxy.localPort() + "/" + PG.getDatabaseName()
                            + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5"
                            + "&currentSchema=" + db.schema,
                    db.runtimeRole, "i1-fixture");
            var tx = SubobjectPostgresFixture.owner(endpoint);
            var participant = participant(f, endpoint, tx);
            var intercepted = afterFinalize(participant, () -> proxy.arm(fault));
            var engine = f.engine(RESULT, intercepted, endpoint, tx);
            var event = f.event(RESULT);
            var claim = f.acquire(RESULT, engine, event);
            var attempt = engine.execute(claim);

            boolean committed = fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
            assertEquals(1, proxy.interceptedCount());
            assertEquals(committed ? 1 : 0, proxy.committedReplyCount());
            assertEquals(Resolution.UNKNOWN, attempt.resolution());
            assertEquals(committed ? RecoveryObservation.COMPLETED : RecoveryObservation.NOT_COMPLETED,
                    attempt.observation());
            assertNull(attempt.cession());
            assertNull(attempt.cessionResolution());
            assertPrefix(f, committed ? 1 : 0);
            assertEquals(committed ? 0 : 1, f.slots(RESULT, event));
            var fresh = f.engines.get(RESULT);
            if (committed) {
                var published = db.oracle.queryForMap("select * from read_availability_event");
                var definition = f.participants.get(RESULT).definition();
                int partition = RoutingV1.partition(
                        RoutingV1.hash(definition.family(), event), definition.partitionCount());
                assertTrue(fresh.acquire(f.lease(RESULT, fresh, partition), List.of(event)).claims().isEmpty());
                assertEquals(published, db.oracle.queryForMap("select * from read_availability_event"));
                // Remove only the publication under schema-owner fault injection. The source,
                // result and covered checkpoint still cannot prove a successful completion.
                db.oracle.execute("alter table read_availability_event disable trigger read_availability_event_immutable");
                db.oracle.update("delete from read_availability_event");
                assertEquals(RecoveryObservation.INCONSISTENT,
                        fresh.observe(claim.claim()));
            } else {
                expireSlot(f, event);
                assertEquals(Status.COMMITTED, fresh.execute(f.acquire(RESULT, fresh, event)).status());
                assertPrefix(f, 1);
                assertEquals(0, f.slots(RESULT, event));
            }
            db.trace.add("Wire COMMIT fault=" + fault + ", physical replies="
                    + proxy.committedReplyCount() + ", attempt=" + attempt);
        }
    }

    @Test
    void expiresBeforeFinalLockAndPublishesOnlyAfterTakeover() throws Exception {
        try (var f = pilot("fencing")) {
            f.enroll();
            var engine = f.engines.get(RESULT);
            var event = f.event(RESULT);
            var lease = f.acquire(RESULT, engine, event);
            expireSlot(f, event);
            var takeover = f.acquire(RESULT, engine, event);
            assertNotEquals(lease.claim().claimToken(), takeover.claim().claimToken());
            assertEquals(Status.CLAIM_LOST, engine.execute(lease).status());
            assertPrefix(f, 0);
            assertEquals(1, f.slots(RESULT, event));
            assertEquals(Status.COMMITTED, engine.execute(takeover).status());
            assertPrefix(f, 1);
            assertEquals(0, f.slots(RESULT, event));
        }
    }

    @Test
    void concurrentDuplicateAttemptsAndAdditionalResultShapesPublishOneEventPerRequest() throws Exception {
        try (var f = pilot("duplicate-and-result-shapes")) {
            var db = f.f.read;
            var store = store(db.source, db.tx);
            var rejected = new CommandTerminalResult(
                    f.pot.request(), "CreatePot", new ExternalIdentity("i2", "actor"), f.pot.pot(),
                    "REJECTED", null, "INVALID_BINDING");
            var inserted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = db.threads.submit(() -> db.tx.write(() -> {
                boolean created = store.insertOrVerify(rejected);
                assertTrue(created);
                inserted.countDown();
                await(release);
                store.requireCompletionReady(rejected, created);
                store.closePublication(rejected.requestId(), created);
                return created;
            }));
            try {
                await(inserted);
                var next = db.threads.submit(() -> db.tx.write(() -> {
                    boolean created = store.insertOrVerify(rejected);
                    store.requireCompletionReady(rejected, created);
                    store.closePublication(rejected.requestId(), created);
                    return created;
                }));
                awaitSqlLock(db, "insert into read_command_result");
                release.countDown();
                assertTrue(get(first));
                assertFalse(get(next));
            } finally {
                release.countDown();
            }
            assertPrefix(f, 1);
            finalizeResult(db, store, new CommandTerminalResult(
                    UUID.randomUUID(), "UpdatePotDetails", rejected.requesterEui(), f.pot.pot(),
                    "UNCHANGED", 1L, null));
            finalizeResult(db, store, new CommandTerminalResult(
                    UUID.randomUUID(), "CreateInvitation", rejected.requesterEui(), f.pot.pot(),
                    "INVITATION_CREATED", null, null));
            assertPrefix(f, 3);
        }
    }

    @Test
    void rejectsInvalidSqlAndV4Progress() throws Exception {
        try (var f = pilot("sql-guards")) {
            var db = f.f.read;
            assertEquals("42501", sqlState(assertThrows(RuntimeException.class,
                    () -> db.sql.execute("update read_availability_counter set writer_xid=pg_current_xact_id()"))));
            assertEquals("42501", sqlState(assertThrows(RuntimeException.class,
                    () -> db.sql.execute("truncate read_availability_event"))));
            assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                    () -> db.sql.execute("update read_availability_counter set position=position+2"))));
            assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                    () -> db.sql.execute("update read_availability_counter set position=position+1"))));
            assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                    () -> db.tx.write(() -> {
                        db.sql.update("update read_availability_counter set position=position+1");
                        return db.sql.update("""
                                insert into read_availability_event values
                                ('COMMAND_RESULT_AVAILABLE',1,?,?)
                                """, UUID.randomUUID(), UUID.randomUUID());
                    }))));
            assertPrefix(f, 0);
        }
        try (var f = new ReadParticipantsFixture(PG, "populated-v4")) {
            f.enroll();
            f.execute(RESULT, f.event(RESULT));
            var failure = assertThrows(FlywayException.class, () -> installPilot(f.f.read));
            assertEquals("23514", sqlState(failure));
            assertNull(f.f.read.oracle.queryForObject(
                    "select to_regclass('read_availability_counter')::text", String.class));
        }
    }

    @Test
    void measuresSerialReadFinalizationWithAndWithoutAvailability() throws Exception {
        // Fresh schemas, equal business work, alternating order. The measured region includes
        // physical commit and excludes WRITE, Discovery, claims and schema creation.
        for (int round = 0; round < 4; round++) {
            boolean pilotFirst = round % 2 == 1;
            measure(round, pilotFirst);
            measure(round, !pilotFirst);
        }
    }

    private static void measure(int round, boolean publish) throws Exception {
        String scenario = "c3-measure-" + round + "-" + (publish ? "pilot" : "baseline");
        try (var db = SubobjectPostgresFixture.currentBaseline(PG, scenario)) {
            if (publish) installPilot(db);
            var store = publish ? store(db.source, db.tx)
                    : new JdbcCommandTerminalResultStore(db.source, db.tx);
            int samples = 25;
            var ids = java.util.stream.IntStream.range(0, samples + 5)
                    .mapToObj(ignored -> UUID.randomUUID()).toList();
            var requester = new ExternalIdentity("i2", "actor");
            for (UUID key : ids.subList(0, 5)) {
                finalizeResult(db, store, new CommandTerminalResult(
                        key, "CreatePot", requester, key, "CHANGED", 1L, null));
            }
            int statementsBefore = db.source.statements.size();
            String startLsn = db.oracle.queryForObject("select pg_current_wal_insert_lsn()::text", String.class);
            var duration = new ArrayList<Long>(samples);
            for (UUID key : ids.subList(5, ids.size())) {
                var value = new CommandTerminalResult(key, "CreatePot", requester, key, "CHANGED", 1L, null);
                long start = System.nanoTime();
                finalizeResult(db, store, value);
                duration.add(System.nanoTime() - start);
            }
            long wal = db.oracle.queryForObject(
                    "select pg_wal_lsn_diff(pg_current_wal_insert_lsn(), cast(? as pg_lsn))::bigint",
                    Long.class, startLsn);
            int statements = db.source.statements.size() - statementsBefore;
            assertEquals(samples + 5, db.oracle.queryForObject("select count(*) from read_command_result", Integer.class));
            if (publish) {
                assertEquals(samples + 5, db.oracle.queryForObject(
                        "select count(*) from read_availability_event", Integer.class));
            }
            long availabilityBytes = publish ? db.oracle.queryForObject("""
                    select pg_total_relation_size('read_availability_event')
                         + pg_total_relation_size('read_availability_counter')
                    """, Long.class) : 0L;
            duration.sort(Long::compareTo);
            String evidence = scenario + " n=" + samples + " sql=" + statements + " walBytes=" + wal
                    + " availabilityBytes=" + availabilityBytes
                    + " p50Nanos=" + duration.get(samples / 2) + " p95Nanos=" + duration.get(23)
                    + " sortedNanos=" + duration;
            System.out.println(evidence);
            db.trace.add(evidence);
        }
    }

    private static void finalizeResult(SubobjectPostgresFixture db, JdbcCommandTerminalResultStore store,
                                       CommandTerminalResult value) {
        db.tx.write(() -> {
            boolean inserted = store.insertOrVerify(value);
            assertTrue(inserted);
            store.requireCompletionReady(value, inserted);
            store.closePublication(value.requestId(), inserted);
            return null;
        });
    }

    private static ReadParticipantsFixture pilot(String scenario) throws Exception {
        var f = new ReadParticipantsFixture(PG, "c3-" + scenario);
        try {
            var db = f.f.read;
            installPilot(db);
            f.engines.put(RESULT, f.engine(RESULT, participant(f, db.source, db.tx), db.source, db.tx));
            return f;
        } catch (RuntimeException | Error failure) {
            f.close();
            throw failure;
        }
    }

    private static void installPilot(SubobjectPostgresFixture db) {
        assertEquals(1, Flyway.configure()
                .dataSource(db.admin)
                .schemas(db.schema)
                .defaultSchema(db.schema)
                .createSchemas(false)
                .locations("classpath:db/read/migration", "classpath:db/read/pilot/migration")
                .placeholders(Map.of(
                        "expectedDatabase", PG.getDatabaseName(),
                        "expectedSchema", db.schema,
                        "expectedAdmin", db.adminRole,
                        "expectedRuntime", db.runtimeRole))
                .load().migrate().migrationsExecuted);
    }

    private static JdbcCommandTerminalResultStore store(DataSource source, SpringAttemptTransactions tx) {
        return JdbcCommandTerminalResultStore.availabilityPilot(source, tx);
    }

    private static MaterializeCommandResult participant(
            ReadParticipantsFixture f, DataSource source, SpringAttemptTransactions tx) {
        return new MaterializeCommandResult(
                new JdbcCommandTerminalSource(f.f.primary, f.f.sourceOwner), store(source, tx));
    }

    private static ConsumptionParticipant afterFinalize(ConsumptionParticipant participant, Runnable action) {
        return new ConsumptionParticipant() {
            @Override
            public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
                return participant.prepare(event, reads).map(prepared -> new Prepared() {
                    @Override
                    public void requireCompletionReady() {
                        prepared.requireCompletionReady();
                    }

                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        prepared.finalizeIn(completion);
                        action.run();
                    }
                });
            }

            @Override
            public Recovery prepareRecovery(UUID event, PreparationReads reads) {
                return participant.prepareRecovery(event, reads);
            }
        };
    }

    private static CommandTerminalResult expected(ReadParticipantsFixture f) {
        return new CommandTerminalResult(
                f.pot.request(), "CreatePot", new ExternalIdentity("i2", "actor"),
                f.pot.pot(), "CHANGED", 1L, null);
    }

    private static void assertPrefix(ReadParticipantsFixture f, int size) {
        var oracle = f.f.read.oracle;
        assertEquals(size, oracle.queryForObject("select count(*) from read_command_result", Integer.class));
        assertEquals((long) size, oracle.queryForObject(
                "select position from read_availability_counter", Long.class));
        assertEquals(java.util.stream.LongStream.rangeClosed(1, size).boxed().toList(),
                oracle.queryForList("select position from read_availability_event order by position", Long.class));
        assertEquals(size, oracle.queryForObject("""
                select count(*) from read_availability_event e
                join read_command_result r on r.request_id=e.projection_key
                """, Integer.class));
    }

    private static void expireSlot(ReadParticipantsFixture f, UUID event) {
        assertEquals(1, f.f.read.oracle.update("""
                update consumption_slot set claimed_until=clock_timestamp()-interval '1 second'
                where consumption_id=? and consumable_id=?
                """, f.participants.get(RESULT).definition().id().value(), event));
    }

    private static void awaitSqlLock(SubobjectPostgresFixture db, String statement) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var blocked = db.sql.queryForList("""
                    select pid from pg_stat_activity
                    where usename = current_user and wait_event_type = 'Lock'
                      and query like ?
                    """, Integer.class, "%" + statement + "%");
            if (!blocked.isEmpty()) {
                db.waitForLock(blocked.getFirst());
                return;
            }
            Thread.sleep(10);
        }
        fail("Second transaction did not wait while executing " + statement);
    }

    private static String sqlState(Throwable fault) {
        for (Throwable cause = fault; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return "";
    }
}
