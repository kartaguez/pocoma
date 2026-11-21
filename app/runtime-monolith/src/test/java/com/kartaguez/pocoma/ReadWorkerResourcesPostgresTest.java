package com.kartaguez.pocoma;
import com.kartaguez.pocoma.compose.polling.materialize.MaterializeSettings;
import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants;
import com.kartaguez.pocoma.compose.web.read.ReadFacadeComposition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.*;
import com.kartaguez.pocoma.infra.postgres.core.consumption.*;
import com.kartaguez.pocoma.infra.postgres.core.history.*;
import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.orchestrator.consumption.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;
import com.kartaguez.pocoma.contract.readquery.ReadLimits;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;

import static com.kartaguez.pocoma.ReadMonolithI8Fixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.read.pot.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.infra.postgres.write.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kartaguez.pocoma.port.transaction.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Tag("slow")
@Testcontainers
class ReadWorkerResourcesPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "log_line_prefix=%m [%p] xid=%x state=%e ");

    @BeforeAll
    static void initializeServer() throws Exception {
        initialize(PG);
    }

    private static Field fixtureField(Object target, String name) throws NoSuchFieldException {
        if (target instanceof com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol)
            return com.kartaguez.pocoma.orchestrator.consumption.AbstractConsumptionProtocol.class
                    .getDeclaredField("engine");
        return target.getClass().getDeclaredField(name);
    }

    private static Object engineTarget(Object target) {
        if (target instanceof com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol)
            return readField(target, "engine");
        return target;
    }

    private static Object readField(Object target, String name) {
        try {
            var field = fixtureField(target, name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Object field(Object target, String name) {
        return readField(engineTarget(target), name);
    }

    static void replace(Object target, String name, Object value) {
        try {
            Object actual = engineTarget(target);
            var field = fixtureField(actual, name);
            field.setAccessible(true);
            field.set(actual, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    static List<ConsumptionWorker.Binding> bindings(ConsumptionWorker worker) {
        return (List<ConsumptionWorker.Binding>) field(worker, "bindings");
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    static void latch(CountDownLatch latch, String stage) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), stage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static ConsumptionWorker.Binding binding(ConsumptionWorker worker, String name) {
        return bindings(worker).stream()
                .filter(b -> b.definition().equals(MaterializeSettings.definition(name)))
                .findFirst()
                .orElseThrow();
    }

    static PotId baseline(
            ReadMonolithI8Fixture f, ConfigurableApplicationContext ctx, boolean actualWrite)
            throws Exception {
        var registration = f.admit("REGISTRATION", "owner", "owner", null);
        if (actualWrite) f.verify(registration);
        else f.produce(registration);
        var pot = new PotId(UUID.randomUUID());
        var command =
                f.admitCommand(
                        "owner",
                        f.ownToken("owner").value(),
                        pot,
                        0,
                        new PotCommand.CreatePot(new Label("baseline")));
        if (actualWrite) f.verifyCommand(command, "CHANGED", null);
        else f.produce(command);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_pot_version where pot_id=?",
                                        Integer.class,
                                        pot.value())
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_pot_balance where pot_id=?",
                                        Integer.class,
                                        pot.value())
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_current_binding where"
                                            + " subject='owner'",
                                        Integer.class)
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_identity_request_result where"
                                            + " request_id=?",
                                        Integer.class,
                                        registration.requestId())
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_command_result where"
                                            + " request_id=?",
                                        Integer.class,
                                        command.requestId())
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_pot_visibility_state where"
                                            + " pot_id=?",
                                        Integer.class,
                                        pot.value())
                                == 1);
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from consumption_slot", Integer.class)
                                == 0);
        return pot;
    }

    private record DrainLatches(
            CountDownLatch prepared,
            CountDownLatch prepareRelease,
            CountDownLatch finalized,
            CountDownLatch finalRelease) {
        DrainLatches() {
            this(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        }

        void releaseAll() {
            prepareRelease.countDown();
            finalRelease.countDown();
        }
    }

    @Test
    void drainAndRestartPreserveDurableProgress() throws Exception {
        for (Class<?> root :
                List.of(
                        PocomaMaterializeWorkerApplication.class,
                        PocomaRuntimeMonolithApplication.class)) {
            verifyNormalDrain(root);
            crashRestart(root);
        }
    }

    private void verifyNormalDrain(Class<?> root) throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.root = root;
            f.select(MaterializeSettings.READ_NAMES, false);
            var ctx = f.start();
            var worker = f.worker(ctx);
            PotId pot = baseline(f, ctx, false);
            var b = binding(worker, "MaterializePotVersion");
            var latches = new DrainLatches();
            installDrainLatches(b, root, latches);
            try {
                executeHeldDrainSequence(f, ctx, worker, pot, b, root, latches);
            } finally {
                latches.releaseAll();
            }
        }
    }

    private static void installDrainLatches(
            ConsumptionWorker.Binding b, Class<?> root, DrainLatches latches) throws Exception {
        var original = (ConsumptionParticipant) field(b.engine(), "participant");
        replace(
                b.engine(),
                "participant",
                new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                        var result = original.prepare(id, reads);
                        latches.prepared().countDown();
                        latch(latches.prepareRelease(), root.getSimpleName() + " preparation release");
                        return result.map(
                                p ->
                                        new Prepared() {
                                            public void finalizeIn(SlotCompletion completion) {
                                                latches.finalized().countDown();
                                                latch(
                                                        latches.finalRelease(),
                                                        root.getSimpleName() + " finalization release");
                                                p.finalizeIn(completion);
                                            }

                                            public void requireCompletionReady() {
                                                p.requireCompletionReady();
                                            }
                                        });
                    }

                    public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                        return original.prepareRecovery(id, reads);
                    }
                });
    }

    private static void executeHeldDrainSequence(
            ReadMonolithI8Fixture f,
            ConfigurableApplicationContext ctx,
            ConsumptionWorker worker,
            PotId pot,
            ConsumptionWorker.Binding b,
            Class<?> root,
            DrainLatches latches)
            throws Exception {
        var update =
                f.admitCommand(
                        "owner",
                        f.ownToken("owner").value(),
                        pot,
                        1,
                        new PotCommand.UpdatePotDetails(new Label("long")));
        f.produce(update);
        latch(latches.prepared(), root.getSimpleName() + " long preparation");
        UUID event =
                f.write.queryForObject(
                        "select event_id from v3_pot_event where request_id=?",
                        UUID.class,
                        update.requestId());
        var deadline =
                f.read.queryForObject(
                        "select claimed_until from consumption_slot where consumption_id=? and consumable_id=?",
                        java.sql.Timestamp.class,
                        b.definition().id().value(),
                        event);
        await(
                () ->
                        Boolean.TRUE.equals(
                                f.read.queryForObject(
                                        "select claimed_until>? from consumption_slot where consumption_id=? and consumable_id=?",
                                        Boolean.class,
                                        deadline,
                                        b.definition().id().value(),
                                        event)),
                Duration.ofSeconds(15));
        f.trace.add("T09 root=" + root.getSimpleName() + " active claim renewed beyond " + deadline);
        latches.prepareRelease().countDown();
        latch(latches.finalized(), "physical final transaction held");
        worker.requestDrain();
        assertEquals(
                WorkerView.State.DRAINING,
                worker.snapshot().state(),
                "cannot report STOPPED while final transaction is retained");
        assertFalse(materialize(ctx).readBusiness.isClosed(), "pool retained until physical drain");
        verifyBacklogRejectedAndQuarantined(f, ctx, worker, pot, root, latches);
    }

    private static void verifyBacklogRejectedAndQuarantined(
            ReadMonolithI8Fixture f,
            ConfigurableApplicationContext ctx,
            ConsumptionWorker worker,
            PotId pot,
            Class<?> root,
            DrainLatches latches)
            throws Exception {
        var next =
                f.admitCommand(
                        "owner",
                        f.ownToken("owner").value(),
                        pot,
                        2,
                        new PotCommand.UpdatePotDetails(new Label("restart-backlog")));
        f.produce(next);
        UUID nextEvent =
                f.write.queryForObject(
                        "select event_id from v3_pot_event where request_id=?",
                        UUID.class,
                        next.requestId());
        assertEquals(
                0,
                f.read.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        nextEvent),
                "no new Discovery after DRAINING");
        latches.finalRelease().countDown();
        f.drain(ctx);
        assertEquals(
                0,
                f.read.queryForObject(
                        "select count(*) from read_pot_version where pot_id=? and pot_version=3",
                        Integer.class,
                        pot.value()),
                "backlog not executed during drain");
        quarantineBalanceSlot(f, nextEvent);
        f.trace.add("T09 normal drain root=" + root.getSimpleName() + " state=" + worker.snapshot());
        f.physical("T09-normal-drain-" + root.getSimpleName());
        f.evidence("T09-normal-drain-" + root.getSimpleName());
    }

    private static void quarantineBalanceSlot(ReadMonolithI8Fixture f, UUID eventId) {
        UUID balance = MaterializeSettings.definition("MaterializePotBalance").id().value();
        f.read.update(
                "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state,attempt_count)"
                        + " select ?,?,h,((h::bigint%4+4)%4)::int,'QUARANTINED',3"
                        + " from(select consumption_hash_v1('POT_EVENT',?) h)x",
                balance,
                eventId,
                eventId);
    }

    static void crashRestart(Class<?> root) throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.root = root;
            f.select(MaterializeSettings.READ_NAMES, false);
            var registration = f.admit("REGISTRATION", "owner", "owner", null);
            f.produce(registration);
            var pot = new PotId(UUID.randomUUID());
            var create =
                    f.admitCommand(
                            "owner",
                            f.ownToken("owner").value(),
                            pot,
                            0,
                            new PotCommand.CreatePot(new Label("before-crash")));
            f.produce(create);
            UUID event =
                    f.write.queryForObject(
                            "select event_id from v3_pot_event where request_id=?",
                            UUID.class,
                            create.requestId());
            quarantineBalanceSlot(f, event);
            try (var child = new ReadI8Child.Child(f, "owner")) {
                child.stage("I8_CHILD_OWNER_READY");
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from read_pot_version where pot_id=? and pot_version=1",
                                                Integer.class,
                                                pot.value())
                                        == 1);
                var leases =
                        f.read.queryForList(
                                "select consumption_id,partition_id,owned_until,owner_token from"
                                        + " partition_ownership where owner_token is not null");
                assertEquals(28, leases.size(), "seven READ/four partitions before crash");
                child.crash();
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from pg_stat_activity where application_name like 'i5-test-%'",
                                                Integer.class)
                                        == 0);
                verifyCrashTakeover(f, root, pot, event, leases, child);
            }
        }
    }

    private static void verifyCrashTakeover(
            ReadMonolithI8Fixture f,
            Class<?> root,
            PotId pot,
            UUID quarantinedEvent,
            List<Map<String, Object>> leases,
            ReadI8Child.Child child)
            throws Exception {
        var next =
                f.admitCommand(
                        "owner",
                        f.ownToken("owner").value(),
                        pot,
                        1,
                        new PotCommand.UpdatePotDetails(new Label("after-crash")));
        f.produce(next);
        long remaining =
                f.read.queryForObject(
                        "select greatest(0,ceil(extract(epoch from(max(owned_until)-clock_timestamp()))*1000))::bigint from partition_ownership",
                        Long.class);
        var began = Instant.now();
        var restarted = f.start();
        await(
                () ->
                        f.worker(restarted).localLeases().getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                                == 28,
                Duration.ofMillis(remaining + 20000));
        await(
                () ->
                        f.read.queryForObject(
                                        "select count(*) from read_pot_version where pot_id=? and pot_version=2",
                                        Integer.class,
                                        pot.value())
                                == 1);
        long takeover = Duration.between(began, Instant.now()).toMillis();
        verifyCrashLeaseIncarnation(f, leases);
        UUID balance = MaterializeSettings.definition("MaterializePotBalance").id().value();
        assertAll(
                () -> assertEquals(
                        1,
                        f.read.queryForObject(
                                "select count(*) from consumption_slot where consumption_id=? and consumable_id=? and state='QUARANTINED' and attempt_count=3",
                                Integer.class,
                                balance,
                                quarantinedEvent),
                        "restart preserves quarantine"),
                () -> assertEquals(
                        2,
                        f.read.queryForObject(
                                "select count(*) from read_pot_version where pot_id=?",
                                Integer.class,
                                pot.value()),
                        "history retained after crash"));
        f.trace.add(
                "T09 CRASH root="
                        + root.getSimpleName()
                        + " child_pid="
                        + child.process.pid()
                        + " lease=240000ms PG remaining="
                        + remaining
                        + " takeover_ms="
                        + takeover);
        f.physical("T09-crash-restart-" + root.getSimpleName());
        f.drain(restarted);
        f.evidence("T09-crash-restart-" + root.getSimpleName());
    }

    private static void verifyCrashLeaseIncarnation(
            ReadMonolithI8Fixture f, List<Map<String, Object>> leases) {
        for (var prior : leases) {
            var actual =
                    f.read.queryForMap(
                            "select owned_until-interval '240 seconds' acquired_at,owner_token<>? changed from partition_ownership where consumption_id=? and partition_id=?",
                            prior.get("owner_token"),
                            prior.get("consumption_id"),
                            prior.get("partition_id"));
            assertEquals(
                    true,
                    actual.get("changed"),
                    "new incarnation after crash " + prior.get("consumption_id") + "/" + prior.get("partition_id"));
            assertFalse(
                    ((java.sql.Timestamp) actual.get("acquired_at"))
                            .before((java.sql.Timestamp) prior.get("owned_until")),
                    "physical takeover never precedes old PostgreSQL deadline");
        }
    }

    /** Real connection/transaction retained across a simulated local transport error. */
    static final class RetainingSource extends DelegatingDataSource {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<Connection> retained = new AtomicReference<>();
        volatile boolean commitFirst;
        volatile int pid;
        volatile String xid;
        volatile Predicate<String> selected = thread -> thread.startsWith("v3-attempt-");

        RetainingSource(HikariDataSource pool) {
            super(pool);
        }

        public Connection getConnection() throws SQLException {
            var actual = super.getConnection();
            var uncertain = new AtomicBoolean();
            return (Connection)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, a) -> handleProxyCall(actual, uncertain, m, a));
        }

        private Object handleProxyCall(
                Connection actual, AtomicBoolean uncertain, Method m, Object[] a)
                throws Throwable {
            if (isCommitFaultTarget(actual, m)) {
                recordBackendXid(actual);
                if (commitFirst) actual.commit();
                uncertain.set(true);
                retained.set(actual);
                throw new SQLException("I8 controlled commit acknowledgement loss", "08006");
            }
            if (uncertain.get()) return handleUncertainCall(actual, m, a);
            return invoke(actual, m, a);
        }

        private Object handleUncertainCall(Connection actual, Method m, Object[] a)
                throws Throwable {
            if (m.getName().equals("rollback"))
                throw new SQLException(
                        "I8 controlled rollback acknowledgement unavailable", "08006");
            if (IGNORED_UNCERTAIN_METHODS.contains(m.getName())) return null;
            return invoke(actual, m, a);
        }

        private boolean isCommitFaultTarget(Connection actual, Method m) throws SQLException {
            return m.getName().equals("commit")
                    && !actual.isReadOnly()
                    && selected.test(Thread.currentThread().getName())
                    && armed.compareAndSet(true, false);
        }

        private void recordBackendXid(Connection actual) throws SQLException {
            try (var s = actual.createStatement();
                    var r =
                            s.executeQuery(
                                    "select pg_backend_pid(),pg_current_xact_id()::text")) {
                r.next();
                pid = r.getInt(1);
                xid = r.getString(2);
            }
        }

        private static final Set<String> IGNORED_UNCERTAIN_METHODS =
                Set.of("close", "setAutoCommit", "abort", "setReadOnly", "setTransactionIsolation");

        void resolve(boolean commit) throws SQLException {
            var actual = retained.getAndSet(null);
            if (actual != null)
                try {
                    // commitFirst already resolved PostgreSQL before losing the ACK.
                    // L2 may have drained and closed the pool meanwhile; only return the loan.
                    if (!commitFirst) {
                        if (commit) actual.commit();
                        else actual.rollback();
                    }
                } finally {
                    try {
                        actual.close();
                    } catch (NullPointerException | SQLException ignored) {
                    }
                }
        }
    }

    static final class HoldingReadSource extends DelegatingDataSource {
        final CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);

        HoldingReadSource(HikariDataSource pool) {
            super(pool);
        }

        public Connection getConnection() throws SQLException {
            var actual = super.getConnection();
            entered.countDown();
            latch(release, "consultation loan release");
            return actual;
        }
    }

    private record RenewalProbe(
            Map<String, java.sql.Timestamp> baselineDeadlines,
            CopyOnWriteArrayList<String> calls,
            AtomicInteger partial,
            AtomicInteger omissions,
            ConcurrentHashMap<String, Connection> blockers) {
        RenewalProbe() {
            this(
                    new HashMap<>(),
                    new CopyOnWriteArrayList<>(),
                    new AtomicInteger(),
                    new AtomicInteger(),
                    new ConcurrentHashMap<>());
        }
    }

    @Test
    void allParticipantsFitPoolsAndRenewalEnvelope() throws Exception {
        for (int batch : List.of(32, 1)) {
            verifyBatchParticipantsAndUnknownCommit(batch);
        }
        verifyRenewUnknownChildCrash();
    }

    private record BatchRun(
            ReadMonolithI8Fixture f,
            ConfigurableApplicationContext ctx,
            ConsumptionWorker worker,
            CapacityView capacity,
            List<ConsumptionWorker.Binding> cohostedBindings,
            PotId pot,
            int batch) {}

    private void verifyBatchParticipantsAndUnknownCommit(int batch) throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.select(MaterializeSettings.READ_NAMES, true);
            f.properties.put("pocoma.v3.worker.lifecycle.batch-size", Integer.toString(batch));
            var ctx = f.start();
            var worker = f.worker(ctx);
            var capacity = materialize(ctx).capacityView().orElseThrow();
            var processWorker = ctx.getBean("processWorker", ConsumptionWorker.class);
            var cohostedBindings = new ArrayList<>(bindings(worker));
            cohostedBindings.addAll(bindings(processWorker));
            assertNotSame(capacity, process(ctx).capacityView().orElseThrow());
            var pot = baseline(f, ctx, true);
            assertEquals(
                    9,
                    cohostedBindings.size(),
                    "batch=" + batch + " seven READ plus two WRITE in independent workers");
            awaitInitialLeases(worker, processWorker);
            recordMaxConnectionsBudget(f, batch);
            var sourceEntered = new CountDownLatch(4);
            var sourceRelease = new CountDownLatch(1);
            installSourcePreparationHolders(worker, batch, sourceEntered, sourceRelease);
            var probe = installRenewalOmissionProxies(f, cohostedBindings);
            var run = new BatchRun(f, ctx, worker, capacity, cohostedBindings, pot, batch);
            runSaturatedEnvelopeAssertions(run, sourceEntered, sourceRelease, probe);
            await(() -> capacity.snapshot().worker() == 0);
            await(() -> f.read.queryForObject("select count(*) from consumption_slot", Integer.class) == 0);
            verifyUnknownCommitResolution(run);
            f.evidence("T10-batch-" + batch);
        }
    }

    private static void awaitInitialLeases(ConsumptionWorker worker, ConsumptionWorker processWorker)
            throws Exception {
        await(
                () ->
                        worker.localLeases()
                                        .getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                                == 28
                                && processWorker.localLeases()
                                                .getOrDefault(ConsumptionDefinition.Authority.WRITE, 0L)
                                        == 8);
    }

    private static void recordMaxConnectionsBudget(ReadMonolithI8Fixture f, int batch) {
        int maxConnections = f.write.queryForObject("show max_connections", Integer.class);
        assertTrue(
                maxConnections >= 38 + 12,
                "two workers=38 plus explicit 12 fixture/admin/observer reserve");
        f.trace.add(
                "T10 batch="
                        + batch
                        + " server max_connections="
                        + maxConnections
                        + " allocated=38 fixture/admin/observer budget=12");
    }

    private static void installSourcePreparationHolders(
            ConsumptionWorker worker,
            int batch,
            CountDownLatch sourceEntered,
            CountDownLatch sourceRelease)
            throws Exception {
        for (String name :
                List.of(
                        "MaterializePotVersion",
                        "MaterializePotBalance",
                        "MaterializePotVisibility",
                        "MaterializeCommandResult")) {
            var engine = binding(worker, name).engine();
            var original = (AttemptTransactions) field(engine, "sourceTransactions");
            var once = new AtomicBoolean();
            replace(
                    engine,
                    "sourceTransactions",
                    new AttemptTransactions() {
                        public <T> T read(Function<RecoveryReadContext, T> call, Guard guard) {
                            return original.read(
                                    c -> {
                                        T value = call.apply(c);
                                        if (once.compareAndSet(false, true)) {
                                            sourceEntered.countDown();
                                            latch(
                                                    sourceRelease,
                                                    "batch=" + batch + " source preparation release " + name);
                                        }
                                        return value;
                                    },
                                    guard);
                        }

                        public <T> T write(Supplier<T> action, Guard guard) {
                            return original.write(action, guard);
                        }

                        public void poison(Throwable cause) {
                            original.poison(cause);
                        }

                        public void requirePublicationClosed() {
                            original.requirePublicationClosed();
                        }
                    });
        }
    }

    private record OmissionContext(
            ConsumptionWorker.Binding binding,
            JdbcTemplate sql,
            PartitionOwnershipStore original,
            RenewalProbe probe,
            Set<UUID> omittedBindings) {}

    private static RenewalProbe installRenewalOmissionProxies(
            ReadMonolithI8Fixture f, List<ConsumptionWorker.Binding> cohostedBindings) throws Exception {
        var probe = new RenewalProbe();
        for (var sql : List.of(f.write, f.read)) {
            for (var row :
                    sql.queryForList(
                            "select consumption_id,partition_id,owned_until from"
                                    + " partition_ownership where owner_token is not null")) {
                probe.baselineDeadlines()
                        .put(
                                row.get("consumption_id") + "/" + row.get("partition_id"),
                                (java.sql.Timestamp) row.get("owned_until"));
            }
        }
        var omittedBindings = ConcurrentHashMap.<UUID>newKeySet();
        for (var b : cohostedBindings) {
            var sql =
                    b.definition().authority() == ConsumptionDefinition.Authority.READ
                            ? f.read
                            : f.write;
            var original = (PartitionOwnershipStore) field(b.engine(), "ownership");
            var omissionCtx = new OmissionContext(b, sql, original, probe, omittedBindings);
            replace(
                    b.engine(),
                    "ownership",
                    Proxy.newProxyInstance(
                            ReadWorkerResourcesPostgresTest.class.getClassLoader(),
                            new Class<?>[] {PartitionOwnershipStore.class},
                            (p, m, a) -> invokeWithOmission(omissionCtx, m, a)));
        }
        return probe;
    }

    private static Object invokeWithOmission(OmissionContext ctx, Method m, Object[] a)
            throws Throwable {
        if (!m.getName().equals("renew") && !m.getName().equals("renewBatch"))
            return invoke(ctx.original(), m, a);
        @SuppressWarnings("unchecked")
        var requested = (List<PartitionLease>) a[1];
        var toOmit = selectOmissions(requested, ctx.omittedBindings());
        var selectedLeases = toOmit.isEmpty() ? requested.subList(0, 1) : List.copyOf(toOmit);
        Object[] selected = a.clone();
        selected[1] = selectedLeases;
        if (!toOmit.isEmpty()) lockOmittedPartitions(ctx.sql(), toOmit, ctx.probe().blockers());
        long began = System.nanoTime();
        @SuppressWarnings("unchecked")
        var result = (List<PartitionLease>) invoke(ctx.original(), m, selected);
        if (requested.size() > result.size()) ctx.probe().partial().incrementAndGet();
        if (result.isEmpty() && !toOmit.isEmpty()) ctx.probe().omissions().addAndGet(toOmit.size());
        releaseOmissionBlockers(selectedLeases, ctx.probe().blockers());
        ctx.probe()
                .calls()
                .add(
                        "authority="
                                + ctx.binding().definition().authority()
                                + " consumption="
                                + ctx.binding().definition().id().value()
                                + " candidates="
                                + requested.stream().map(PartitionLease::partitionId).toList()
                                + " returned="
                                + result.stream().map(PartitionLease::partitionId).toList()
                                + " call_ms="
                                + (System.nanoTime() - began) / 1000000);
        return result;
    }

    private static List<PartitionLease> selectOmissions(
            List<PartitionLease> requested, Set<UUID> omittedBindings) {
        var toOmit = new ArrayList<PartitionLease>();
        var seenInCall = new HashSet<UUID>();
        for (var candidate : requested) {
            var cid = candidate.consumptionId().value();
            if (seenInCall.add(cid) && omittedBindings.add(cid)) toOmit.add(candidate);
        }
        return toOmit;
    }

    private static void lockOmittedPartitions(
            JdbcTemplate sql, List<PartitionLease> toOmit, ConcurrentHashMap<String, Connection> blockers)
            throws SQLException {
        var blocker = sql.getDataSource().getConnection();
        blocker.setAutoCommit(false);
        try (var statement = blocker.createStatement()) {
            statement.execute("set local lock_timeout='500ms'");
        }
        for (var candidate : toOmit) {
            try (var statement =
                    blocker.prepareStatement(
                            "select 1 from partition_ownership where consumption_id=? and"
                                    + " partition_id=? for update")) {
                statement.setObject(1, candidate.consumptionId().value());
                statement.setInt(2, candidate.partitionId());
                statement.executeQuery().close();
            }
            blockers.put(
                    candidate.consumptionId().value() + "/" + candidate.partitionId(), blocker);
        }
    }

    private static void releaseOmissionBlockers(
            List<PartitionLease> selectedLeases, ConcurrentHashMap<String, Connection> blockers)
            throws SQLException {
        var toClose = new LinkedHashSet<Connection>();
        for (var candidate : selectedLeases) {
            var c = blockers.remove(candidate.consumptionId().value() + "/" + candidate.partitionId());
            if (c != null) toClose.add(c);
        }
        for (var c : toClose) {
            c.rollback();
            c.close();
        }
    }

    private static void runSaturatedEnvelopeAssertions(
            BatchRun run,
            CountDownLatch sourceEntered,
            CountDownLatch sourceRelease,
            RenewalProbe probe)
            throws Exception {
        var loans = new ArrayList<Connection>();
        var heldRead = new HoldingReadSource(materialize(run.ctx()).readBusiness);
        var readOwner = new SpringAttemptTransactions(heldRead, manager(heldRead), 60);
        var facade =
                ReadFacadeComposition.create(
                        heldRead,
                        readOwner,
                        new ReadLimits(50, 20, 16000000),
                        Set.copyOf(MaterializeSettings.READ_NAMES),
                        Optional::empty);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var change =
                        run.f().admitCommand(
                                "owner",
                                run.f().ownToken("owner").value(),
                                run.pot(),
                                1,
                                new PotCommand.UpdatePotDetails(new Label("saturated")));
                run.f().verifyCommand(change, "CHANGED", null);
                latch(sourceEntered, "batch=" + run.batch() + " four real preparations held");
                var a =
                        threads.submit(
                                () -> facade.current(run.f().eui("owner"), run.f().ownToken("owner").value(), run.pot().value(), 0));
                var b =
                        threads.submit(
                                () -> facade.current(run.f().eui("owner"), run.f().ownToken("owner").value(), run.pot().value(), 0));
                latch(heldRead.entered, "two actual READ consultation loans");
                assertInstanceOf(
                        ReadPotResult.Unavailable.class,
                        facade.current(run.f().eui("owner"), "not-used", run.pot().value(), 0),
                        "third consultation rejected by quota without borrowing control");
                for (int n = 0; n < 4; n++) loans.add(materialize(run.ctx()).readBusiness.getConnection());
                assertSaturatedPoolMetrics(run.ctx(), run.capacity());
                awaitAllCandidatesRenewed(run, probe);
                assertAll(
                        () -> assertTrue(probe.omissions().get() >= 8, "one physical omission per binding"),
                        () -> assertTrue(probe.partial().get() > 0, "partial renewal calls observed"),
                        () -> assertTrue(probe.blockers().isEmpty(), "every omitted candidate got its bounded retry"));
                run.f().trace.add(
                        "T10 batch="
                                + run.batch()
                                + " MW="
                                + run.capacity().snapshot()
                                + " all 36 candidates renewed, partial="
                                + probe.partial()
                                + " omissions="
                                + probe.omissions());
                run.f().trace.addAll(probe.calls());
                run.f().physical("T10-batch-" + run.batch());
                heldRead.release.countDown();
                assertInstanceOf(ReadPotResult.Available.class, a.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ReadPotResult.Available.class, b.get(10, TimeUnit.SECONDS));
            } finally {
                cleanupSaturatedEnvelope(heldRead, loans, sourceRelease, probe);
            }
        }
    }

    private static void cleanupSaturatedEnvelope(
            HoldingReadSource heldRead,
            List<Connection> loans,
            CountDownLatch sourceRelease,
            RenewalProbe probe)
            throws SQLException {
        heldRead.release.countDown();
        for (var loan : loans) loan.close();
        sourceRelease.countDown();
        for (var blocker : new LinkedHashSet<>(probe.blockers().values())) {
            blocker.rollback();
            blocker.close();
        }
        probe.blockers().clear();
    }

    private static void assertSaturatedPoolMetrics(
            ConfigurableApplicationContext ctx, CapacityView capacity) {
        assertEquals(4, capacity.snapshot().worker(), "MW4 saturated");
        capacity.snapshot()
                .consumptions()
                .forEach((id, count) -> assertTrue(count <= 1, "MC1 " + id));
        assertEquals(
                6,
                materialize(ctx).readBusiness.getHikariPoolMXBean().getActiveConnections(),
                "READ business upper-bound saturation");
        int writeActive =
                materialize(ctx).writeSourceRead.getHikariPoolMXBean().getActiveConnections();
        assertTrue(
                writeActive >= 4 && writeActive <= 6,
                "WRITE source preparations plus discovery reserves: " + writeActive);
    }

    private static void awaitAllCandidatesRenewed(BatchRun run, RenewalProbe probe)
            throws Exception {
        try {
            await(
                    () -> allPartitionsRenewed(run.f(), run.cohostedBindings(), probe.baselineDeadlines()),
                    Duration.ofSeconds(18));
        } catch (AssertionError failure) {
            run.f().trace.add(
                    "T10 FAILED renewal batch="
                            + run.batch()
                            + " initial="
                            + probe.baselineDeadlines()
                            + " worker="
                            + run.worker().snapshot()
                            + " localLeases="
                            + run.worker().localLeases());
            run.f().trace.addAll(probe.calls());
            run.f().physical("T10-renewal-FAIL");
            run.f().evidence("T10-renewal-FAIL");
            throw failure;
        }
    }

    private static boolean allPartitionsRenewed(
            ReadMonolithI8Fixture f,
            List<ConsumptionWorker.Binding> cohostedBindings,
            Map<String, java.sql.Timestamp> baselineDeadlines) {
        for (var binding : cohostedBindings) {
            var sql =
                    binding.definition().authority() == ConsumptionDefinition.Authority.READ
                            ? f.read
                            : f.write;
            for (int partition = 0; partition < 4; partition++) {
                var prior = baselineDeadlines.get(binding.definition().id().value() + "/" + partition);
                if (prior == null
                        || !Boolean.TRUE.equals(
                                sql.queryForObject(
                                        "select owned_until>? and owned_until>clock_timestamp()"
                                                + " from partition_ownership where consumption_id=? and"
                                                + " partition_id=?",
                                        Boolean.class,
                                        prior,
                                        binding.definition().id().value(),
                                        partition))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void verifyUnknownCommitResolution(BatchRun run) throws Exception {
        var fault = new RetainingSource(materialize(run.ctx()).readBusiness);
        var owner = new SpringAttemptTransactions(fault, manager(fault), 60);
        var resources = materialize(run.ctx());
        var participant = ReadParticipants.select(
                "MaterializePotVersion", resources.writeSourceRead, resources.sourceReads, fault, owner);
        var selected = binding(run.worker(), "MaterializePotVersion");
        replace(selected.engine(), "transactions", owner);
        replace(selected.engine(), "slots", new JdbcConsumptionSlotStore(fault));
        replace(
                field(selected.engine(), "recoveryPolicy"),
                "admissions",
                new JdbcDiscoveryAdmissionStore(
                        fault,
                        new DiscoverySettings(
                                128, run.batch(), run.batch(), 64, Duration.ofMillis(500), Duration.ofSeconds(2))));
        replace(selected.engine(), "participant", participant.participant());
        for (boolean committed : List.of(run.batch() == 32)) {
            fault.commitFirst = committed;
            fault.armed.set(true);
            var change =
                    run.f().admitCommand(
                            "owner",
                            run.f().ownToken("owner").value(),
                            run.pot(),
                            2,
                            new PotCommand.UpdatePotDetails(new Label("unknown-" + committed)));
            run.f().verifyCommand(change, "CHANGED", null);
            await(() -> fault.retained.get() != null);
            if (committed) {
                verifyCommittedAckLoss(run, fault);
            } else {
                verifyUncommittedAckLoss(run, fault);
            }
            run.f().trace.add(
                    "T10 UNKNOWN physical_committed="
                            + committed
                            + " PID="
                            + fault.pid
                            + " XID="
                            + fault.xid
                            + " state="
                            + run.worker().snapshot());
        }
    }

    private static void verifyCommittedAckLoss(BatchRun run, RetainingSource fault)
            throws Exception {
        await(
                () ->
                        run.f().read.queryForObject(
                                        "select count(*) from read_pot_version where pot_id=? and pot_version=3",
                                        Integer.class,
                                        run.pot().value())
                                == 1);
        fault.resolve(false);
        await(() -> run.capacity().snapshot().worker() == 0);
        run.f().drain(run.ctx());
        assertEquals(
                1,
                run.f().read.queryForObject(
                        "select count(*) from read_pot_version where pot_id=? and pot_version=3",
                        Integer.class,
                        run.pot().value()),
                "ACK loss after commit has one immutable effect");
    }

    private static void verifyUncommittedAckLoss(BatchRun run, RetainingSource fault)
            throws Exception {
        assertAll(
                () -> assertEquals(
                        0,
                        run.f().read.queryForObject(
                                "select count(*) from read_pot_version where pot_id=? and pot_version=3",
                                Integer.class,
                                run.pot().value()),
                        "uncommitted effects invisible externally"),
                () -> assertTrue(run.capacity().snapshot().worker() >= 1, "UNKNOWN retains capacity"));
        run.worker().requestDrain();
        assertAll(
                () -> assertEquals(WorkerView.State.DRAINING, run.worker().snapshot().state(), "UNKNOWN cannot fake STOPPED"),
                () -> assertFalse(materialize(run.ctx()).readBusiness.isClosed(), "UNKNOWN physical loan retained"),
                () -> assertEquals(
                        1,
                        run.f().read.queryForObject(
                                "select count(*) from pg_stat_activity where pid=? and xact_start is not null",
                                Integer.class,
                                fault.pid),
                        "unresolved PostgreSQL transaction witness"));
        fault.resolve(false);
        run.f().drain(run.ctx());
        assertEquals(
                0,
                run.f().read.queryForObject(
                        "select count(*) from read_pot_version where pot_id=? and pot_version=3",
                        Integer.class,
                        run.pot().value()),
                "no speculative effect after physical rollback");
    }

    private void verifyRenewUnknownChildCrash() throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.select(MaterializeSettings.READ_NAMES, true);
            try (var child = new ReadI8Child.Child(f, "renew-unknown")) {
                child.stage("I8_RENEW_UNKNOWN");
                var matcher =
                        java.util.regex.Pattern.compile("I8_RENEW_UNKNOWN pid=(\\d+) xid=([^ ]+)")
                                .matcher(child.output());
                assertTrue(matcher.find(), "physical renewal PID/XID marker");
                int pid = Integer.parseInt(matcher.group(1));
                assertTrue(
                        child.output().contains("state=DRAINING")
                                && child.output().contains("poolClosed=false"),
                        "renewal UNKNOWN retains call and resources, never false STOPPED");
                assertEquals(
                        1,
                        f.read.queryForObject(
                                "select count(*) from pg_stat_activity where pid=? and xact_start"
                                        + " is not null",
                                Integer.class,
                                pid),
                        "retained renewal is a live PostgreSQL transaction");
                child.crash();
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from pg_stat_activity where pid=?",
                                                Integer.class,
                                                pid)
                                        == 0);
                f.trace.add(
                        "T10 renewal UNKNOWN PID="
                                + pid
                                + " XID="
                                + matcher.group(2)
                                + " only external CRASH ended the unresolved call");
                f.physical("T10-renewal-UNKNOWN");
                f.evidence("T10-renewal-UNKNOWN");
            }
        }
    }
}
