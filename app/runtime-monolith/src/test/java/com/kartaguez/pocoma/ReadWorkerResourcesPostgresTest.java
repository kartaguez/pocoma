package com.kartaguez.pocoma;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.contract.readquery.ReadLimitsV3;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;

import static com.kartaguez.pocoma.ReadMonolithI8Fixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
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

    static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void replace(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<V3ConsumptionWorker.Binding> bindings(V3ConsumptionWorker worker) {
        return (List<V3ConsumptionWorker.Binding>) field(worker, "bindings");
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

    static V3ConsumptionWorker.Binding binding(V3ConsumptionWorker worker, String name) {
        return bindings(worker).stream()
                .filter(b -> b.definition().equals(V3ConsumptionWorkerSettings.definition(name)))
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

    @Test
    void drainAndRestartPreserveDurableProgress() throws Exception {
        for (Class<?> root :
                List.of(
                        PocomaTaskConsumptionWorkerApplication.class,
                        PocomaRuntimeMonolithApplication.class)) {
            try (var f = new ReadMonolithI8Fixture(PG)) {
                f.root = root;
                f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
                var ctx = f.start();
                var worker = f.worker(ctx);
                PotId pot = baseline(f, ctx, false);
                var b = binding(worker, "MaterializePotVersion");
                var original = (ConsumptionParticipant) field(b.engine(), "participant");
                var prepared = new CountDownLatch(1);
                var prepareRelease = new CountDownLatch(1);
                var finalized = new CountDownLatch(1);
                var finalRelease = new CountDownLatch(1);
                replace(
                        b.engine(),
                        "participant",
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                                var result = original.prepare(id, reads);
                                prepared.countDown();
                                latch(
                                        prepareRelease,
                                        root.getSimpleName() + " preparation release");
                                return result.map(
                                        p ->
                                                new Prepared() {
                                                    public void finalizeIn(
                                                            SlotCompletion completion) {
                                                        finalized.countDown();
                                                        latch(
                                                                finalRelease,
                                                                root.getSimpleName()
                                                                        + " finalization release");
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
                try {
                    var update =
                            f.admitCommand(
                                    "owner",
                                    f.ownToken("owner").value(),
                                    pot,
                                    1,
                                    new PotCommand.UpdatePotDetails(new Label("long")));
                    f.produce(update);
                    latch(prepared, root.getSimpleName() + " long preparation");
                    UUID event =
                            f.write.queryForObject(
                                    "select event_id from v3_pot_event where request_id=?",
                                    UUID.class,
                                    update.requestId());
                    var deadline =
                            f.read.queryForObject(
                                    "select claimed_until from consumption_slot where"
                                        + " consumption_id=? and consumable_id=?",
                                    java.sql.Timestamp.class,
                                    b.definition().id().value(),
                                    event);
                    await(
                            () ->
                                    f.read.queryForObject(
                                            "select claimed_until>? from consumption_slot where"
                                                + " consumption_id=? and consumable_id=?",
                                            Boolean.class,
                                            deadline,
                                            b.definition().id().value(),
                                            event),
                            Duration.ofSeconds(15));
                    f.trace.add(
                            "T09 root="
                                    + root.getSimpleName()
                                    + " active claim renewed beyond "
                                    + deadline);
                    prepareRelease.countDown();
                    latch(finalized, "physical final transaction held");
                    worker.requestDrain();
                    assertEquals(
                            WorkerView.State.DRAINING,
                            worker.snapshot().state(),
                            "cannot report STOPPED while final transaction is retained");
                    assertFalse(
                            ctx.getBean("readBusiness", HikariDataSource.class).isClosed(),
                            "pool retained until physical drain");
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
                    finalRelease.countDown();
                    f.drain(ctx);
                    assertEquals(
                            0,
                            f.read.queryForObject(
                                    "select count(*) from read_pot_version where pot_id=? and"
                                        + " pot_version=3",
                                    Integer.class,
                                    pot.value()),
                            "backlog not executed during drain");
                    // A real published event is explicitly quarantined administratively before
                    // restart.
                    UUID balance =
                            V3ConsumptionWorkerSettings.definition("MaterializePotBalance")
                                    .id()
                                    .value();
                    f.read.update(
                            "insert into"
                                + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state,attempt_count)"
                                + " select ?,?,h,((h::bigint%4+4)%4)::int,'QUARANTINED',3"
                                + " from(select consumption_hash_v1('POT_EVENT',?) h)x",
                            balance, nextEvent, nextEvent);
                    f.trace.add(
                            "T09 normal drain root="
                                    + root.getSimpleName()
                                    + " state="
                                    + worker.snapshot());
                    f.physical("T09-normal-drain-" + root.getSimpleName());
                    f.evidence("T09-normal-drain-" + root.getSimpleName());
                } finally {
                    prepareRelease.countDown();
                    finalRelease.countDown();
                }
            }
            crashRestart(root);
        }
    }

    static void crashRestart(Class<?> root) throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.root = root;
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
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
            UUID balance =
                    V3ConsumptionWorkerSettings.definition("MaterializePotBalance").id().value();
            f.read.update(
                    "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state,attempt_count)"
                        + " select ?,?,h,((h::bigint%4+4)%4)::int,'QUARANTINED',3 from(select"
                        + " consumption_hash_v1('POT_EVENT',?) h)x",
                    balance, event, event);
            try (var child = new ReadI8Child.Child(f, "owner")) {
                child.stage("I8_CHILD_OWNER_READY");
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from read_pot_version where"
                                                    + " pot_id=? and pot_version=1",
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
                                                "select count(*) from pg_stat_activity where"
                                                    + " application_name like 'i5-test-%'",
                                                Integer.class)
                                        == 0);
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
                                "select greatest(0,ceil(extract(epoch"
                                    + " from(max(owned_until)-clock_timestamp()))*1000))::bigint"
                                    + " from partition_ownership",
                                Long.class);
                var began = Instant.now();
                var restarted = f.start();
                await(
                        () ->
                                f.worker(restarted)
                                                .localLeases()
                                                .getOrDefault(
                                                        ConsumptionDefinition.Authority.READ, 0L)
                                        == 28,
                        Duration.ofMillis(remaining + 20000));
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from read_pot_version where"
                                                    + " pot_id=? and pot_version=2",
                                                Integer.class,
                                                pot.value())
                                        == 1);
                long takeover = Duration.between(began, Instant.now()).toMillis();
                for (var prior : leases) {
                    var actual =
                            f.read.queryForMap(
                                    "select owned_until-interval '240 seconds'"
                                        + " acquired_at,owner_token<>? changed from"
                                        + " partition_ownership where consumption_id=? and"
                                        + " partition_id=?",
                                    prior.get("owner_token"),
                                    prior.get("consumption_id"),
                                    prior.get("partition_id"));
                    assertEquals(
                            true,
                            actual.get("changed"),
                            "new incarnation after crash "
                                    + prior.get("consumption_id")
                                    + "/"
                                    + prior.get("partition_id"));
                    assertFalse(
                            ((java.sql.Timestamp) actual.get("acquired_at"))
                                    .before((java.sql.Timestamp) prior.get("owned_until")),
                            "physical takeover never precedes old PostgreSQL deadline");
                }
                assertEquals(
                        1,
                        f.read.queryForObject(
                                "select count(*) from consumption_slot where consumption_id=? and"
                                    + " consumable_id=? and state='QUARANTINED' and"
                                    + " attempt_count=3",
                                Integer.class,
                                balance,
                                event),
                        "restart preserves quarantine");
                assertEquals(
                        2,
                        f.read.queryForObject(
                                "select count(*) from read_pot_version where pot_id=?",
                                Integer.class,
                                pot.value()),
                        "history retained after crash");
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
                            (p, m, a) -> {
                                if (m.getName().equals("commit")
                                        && !actual.isReadOnly()
                                        && selected.test(Thread.currentThread().getName())
                                        && armed.compareAndSet(true, false)) {
                                    try (var s = actual.createStatement();
                                            var r =
                                                    s.executeQuery(
                                                            "select"
                                                                + " pg_backend_pid(),pg_current_xact_id()::text")) {
                                        r.next();
                                        pid = r.getInt(1);
                                        xid = r.getString(2);
                                    }
                                    if (commitFirst) actual.commit();
                                    uncertain.set(true);
                                    retained.set(actual);
                                    throw new SQLException(
                                            "I8 controlled commit acknowledgement loss", "08006");
                                }
                                if (uncertain.get()) {
                                    if (m.getName().equals("rollback"))
                                        throw new SQLException(
                                                "I8 controlled rollback acknowledgement"
                                                    + " unavailable",
                                                "08006");
                                    if (Set.of(
                                                    "close",
                                                    "setAutoCommit",
                                                    "abort",
                                                    "setReadOnly",
                                                    "setTransactionIsolation")
                                            .contains(m.getName())) return null;
                                }
                                return invoke(actual, m, a);
                            });
        }

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

    @Test
    void allParticipantsFitPoolsAndRenewalEnvelope() throws Exception {
        for (int batch : List.of(32, 1))
            try (var f = new ReadMonolithI8Fixture(PG)) {
                f.select(V3ConsumptionWorkerSettings.READ_NAMES, true);
                f.properties.put("pocoma.v3.worker.lifecycle.batch-size", Integer.toString(batch));
                var ctx = f.start();
                var worker = f.worker(ctx);
                var capacity = ctx.getBean(WorkerCapacity.class);
                var pot = baseline(f, ctx, true);
                assertEquals(
                        9,
                        bindings(worker).size(),
                        "batch=" + batch + " seven READ plus two WRITE in one Worker");
                await(
                        () ->
                                worker.localLeases()
                                                        .getOrDefault(
                                                                ConsumptionDefinition.Authority
                                                                        .READ,
                                                                0L)
                                                == 28
                                        && worker.localLeases()
                                                        .getOrDefault(
                                                                ConsumptionDefinition.Authority
                                                                        .WRITE,
                                                                0L)
                                                == 8);

                int maxConnections = f.write.queryForObject("show max_connections", Integer.class);
                assertTrue(
                        maxConnections >= 32 + 12,
                        "one instance=32 plus explicit 12 fixture/admin/observer reserve");
                f.trace.add(
                        "T10 batch="
                                + batch
                                + " server max_connections="
                                + maxConnections
                                + " allocated=32 fixture/admin/observer budget=12");
                // Each READ source preparation holds one actual WRITE business loan after its SQL.
                var sourceEntered = new CountDownLatch(4);
                var sourceRelease = new CountDownLatch(1);
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
                                public <T> T read(
                                        Function<RecoveryReadContext, T> call, Guard guard) {
                                    return original.read(
                                            c -> {
                                                T value = call.apply(c);
                                                if (once.compareAndSet(false, true)) {
                                                    sourceEntered.countDown();
                                                    latch(
                                                            sourceRelease,
                                                            "batch="
                                                                    + batch
                                                                    + " source preparation release "
                                                                    + name);
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
                var baselineDeadlines = new HashMap<String, java.sql.Timestamp>();
                for (var sql : List.of(f.write, f.read))
                    for (var row :
                            sql.queryForList(
                                    "select consumption_id,partition_id,owned_until from"
                                        + " partition_ownership where owner_token is not null"))
                        baselineDeadlines.put(
                                row.get("consumption_id") + "/" + row.get("partition_id"),
                                (java.sql.Timestamp) row.get("owned_until"));
                var calls = new CopyOnWriteArrayList<String>();
                var partial = new AtomicInteger();
                var omissions = new AtomicInteger();
                // Real SKIP LOCKED omission for partition zero of every binding; one useful
                // candidate per
                // physical call.
                var blockers = new ConcurrentHashMap<String, Connection>();
                for (var b : bindings(worker)) {
                    var sql =
                            b.definition().authority() == ConsumptionDefinition.Authority.READ
                                    ? f.read
                                    : f.write;
                    var omitOnce = new AtomicBoolean();
                    var original = (PartitionOwnershipStore) field(b.engine(), "ownership");
                    replace(
                            b.engine(),
                            "ownership",
                            Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {PartitionOwnershipStore.class},
                                    (p, m, a) -> {
                                        if (!m.getName().equals("renew"))
                                            return invoke(original, m, a);
                                        @SuppressWarnings("unchecked")
                                        var requested = (List<PartitionLease>) a[1];
                                        var one = requested.subList(0, 1);
                                        Object[] selected = a.clone();
                                        selected[1] = one;
                                        // Hold only during this renewal SQL. A long pre-existing
                                        // lock would also
                                        // obstruct Discovery/acquisition and exceed this scenario's
                                        // omission
                                        // hypothesis.
                                        if (omitOnce.compareAndSet(false, true)) {
                                            var candidate = one.getFirst();
                                            var blocker = sql.getDataSource().getConnection();
                                            blocker.setAutoCommit(false);
                                            try (var statement = blocker.createStatement()) {
                                                statement.execute("set local lock_timeout='500ms'");
                                            }
                                            try (var statement =
                                                    blocker.prepareStatement(
                                                            "select 1 from partition_ownership"
                                                                + " where consumption_id=? and"
                                                                + " partition_id=? for update")) {
                                                statement.setObject(
                                                        1, candidate.consumptionId().value());
                                                statement.setInt(2, candidate.partitionId());
                                                statement.executeQuery().close();
                                            }
                                            blockers.put(
                                                    candidate.consumptionId().value()
                                                            + "/"
                                                            + candidate.partitionId(),
                                                    blocker);
                                        }
                                        long began = System.nanoTime();
                                        @SuppressWarnings("unchecked")
                                        var result =
                                                (List<PartitionLease>)
                                                        invoke(original, m, selected);
                                        if (requested.size() > result.size())
                                            partial.incrementAndGet();
                                        if (result.isEmpty()) omissions.incrementAndGet();
                                        for (var candidate : one) {
                                            var c =
                                                    blockers.remove(
                                                            candidate.consumptionId().value()
                                                                    + "/"
                                                                    + candidate.partitionId());
                                            if (c != null) {
                                                c.rollback();
                                                c.close();
                                            }
                                        }
                                        calls.add(
                                                "authority="
                                                        + b.definition().authority()
                                                        + " consumption="
                                                        + b.definition().id().value()
                                                        + " candidates="
                                                        + requested.stream()
                                                                .map(PartitionLease::partitionId)
                                                                .toList()
                                                        + " returned="
                                                        + result.stream()
                                                                .map(PartitionLease::partitionId)
                                                                .toList()
                                                        + " call_ms="
                                                        + (System.nanoTime() - began) / 1000000);
                                        return result;
                                    }));
                }
                var loans = new ArrayList<Connection>();
                var heldRead =
                        new HoldingReadSource(ctx.getBean("readBusiness", HikariDataSource.class));
                var readOwner = new SpringAttemptTransactions(heldRead, manager(heldRead), 60);
                var facade =
                        V3ReadFacadeComposition.create(
                                heldRead,
                                readOwner,
                                new ReadLimitsV3(50, 20, 16000000),
                                Set.copyOf(V3ConsumptionWorkerSettings.READ_NAMES),
                                Optional::empty);
                try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                    try {
                        var change =
                                f.admitCommand(
                                        "owner",
                                        f.ownToken("owner").value(),
                                        pot,
                                        1,
                                        new PotCommand.UpdatePotDetails(new Label("saturated")));
                        f.verifyCommand(change, "CHANGED", null);
                        latch(sourceEntered, "batch=" + batch + " four real preparations held");
                        var a =
                                threads.submit(
                                        () ->
                                                facade.current(
                                                        f.eui("owner"),
                                                        f.ownToken("owner").value(),
                                                        pot.value(),
                                                        0));
                        var b =
                                threads.submit(
                                        () ->
                                                facade.current(
                                                        f.eui("owner"),
                                                        f.ownToken("owner").value(),
                                                        pot.value(),
                                                        0));
                        latch(heldRead.entered, "two actual READ consultation loans");
                        assertInstanceOf(
                                ReadPotV3Result.Unavailable.class,
                                facade.current(f.eui("owner"), "not-used", pot.value(), 0),
                                "third consultation rejected by quota without borrowing control");
                        // READ upper-bound loans stand for four effects plus the two consultation
                        // loans.
                        // WRITE keeps its two protocol reserves for Discovery/observations: no
                        // WRITE facade
                        // exists.
                        for (int n = 0; n < 4; n++)
                            loans.add(
                                    ctx.getBean("readBusiness", HikariDataSource.class)
                                            .getConnection());
                        assertEquals(4, capacity.snapshot().worker(), "MW4 saturated");
                        capacity.snapshot()
                                .consumptions()
                                .forEach((id, count) -> assertTrue(count <= 1, "MC1 " + id));
                        assertEquals(
                                6,
                                ctx.getBean("readBusiness", HikariDataSource.class)
                                        .getHikariPoolMXBean()
                                        .getActiveConnections(),
                                "READ business upper-bound saturation");
                        int writeActive =
                                ctx.getBean("writeBusiness", HikariDataSource.class)
                                        .getHikariPoolMXBean()
                                        .getActiveConnections();
                        assertTrue(
                                writeActive >= 4 && writeActive <= 6,
                                "WRITE preparations plus protocol reserves: " + writeActive);
                        try {
                            await(
                                    () -> {
                                        for (var binding : bindings(worker)) {
                                            var sql =
                                                    binding.definition().authority()
                                                                    == ConsumptionDefinition
                                                                            .Authority.READ
                                                            ? f.read
                                                            : f.write;
                                            for (int partition = 0; partition < 4; partition++) {
                                                var prior =
                                                        baselineDeadlines.get(
                                                                binding.definition().id().value()
                                                                        + "/"
                                                                        + partition);
                                                if (prior == null
                                                        || !sql.queryForObject(
                                                                "select owned_until>? and"
                                                                    + " owned_until>clock_timestamp()"
                                                                    + " from partition_ownership"
                                                                    + " where consumption_id=? and"
                                                                    + " partition_id=?",
                                                                Boolean.class,
                                                                prior,
                                                                binding.definition().id().value(),
                                                                partition)) return false;
                                            }
                                        }
                                        return true;
                                    },
                                    Duration.ofSeconds(18));
                        } catch (AssertionError failure) {
                            f.trace.add(
                                    "T10 FAILED renewal batch="
                                            + batch
                                            + " initial="
                                            + baselineDeadlines
                                            + " worker="
                                            + worker.snapshot()
                                            + " localLeases="
                                            + worker.localLeases());
                            f.trace.addAll(calls);
                            f.physical("T10-renewal-FAIL");
                            f.evidence("T10-renewal-FAIL");
                            throw failure;
                        }
                        assertTrue(omissions.get() >= 8, "one physical omission per binding");
                        assertTrue(partial.get() > 0, "partial renewal calls observed");
                        assertTrue(
                                blockers.isEmpty(),
                                "every omitted candidate got its bounded retry");
                        f.trace.add(
                                "T10 batch="
                                        + batch
                                        + " MW="
                                        + capacity.snapshot()
                                        + " all 32 candidates renewed, partial="
                                        + partial
                                        + " omissions="
                                        + omissions);
                        f.trace.addAll(calls);
                        f.physical("T10-batch-" + batch);
                        heldRead.release.countDown();
                        assertInstanceOf(
                                ReadPotV3Result.Available.class, a.get(10, TimeUnit.SECONDS));
                        assertInstanceOf(
                                ReadPotV3Result.Available.class, b.get(10, TimeUnit.SECONDS));
                    } finally {
                        heldRead.release.countDown();
                        for (var loan : loans) loan.close();
                        sourceRelease.countDown();
                        for (var blocker : blockers.values()) {
                            blocker.rollback();
                            blocker.close();
                        }
                        blockers.clear();
                    }
                }
                await(() -> capacity.snapshot().worker() == 0);
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from consumption_slot",
                                                Integer.class)
                                        == 0);
                {
                    // Commit uncertain after/before PostgreSQL resolution, using the same real
                    // business pool.
                    var fault =
                            new RetainingSource(
                                    ctx.getBean("readBusiness", HikariDataSource.class));
                    var owner = new SpringAttemptTransactions(fault, manager(fault), 60);
                    var resources = ctx.getBean("v3WorkerResources");
                    var participants =
                            V3ReadParticipants.create(
                                    ctx.getBean("writeBusiness", HikariDataSource.class),
                                    (SpringAttemptTransactions) field(resources, "writeEffects"),
                                    fault,
                                    owner);
                    var selected = binding(worker, "MaterializePotVersion");
                    replace(selected.engine(), "transactions", owner);
                    replace(selected.engine(), "slots", new JdbcConsumptionSlotStore(fault));
                    replace(
                            selected.engine(),
                            "admissions",
                            new JdbcDiscoveryAdmissionStore(
                                    fault,
                                    new DiscoverySettings(
                                            128,
                                            batch,
                                            batch,
                                            64,
                                            Duration.ofMillis(500),
                                            Duration.ofSeconds(2))));
                    replace(
                            selected.engine(),
                            "participant",
                            participants.get(V3ReadParticipants.Name.POT_VERSION).participant());
                    for (boolean committed : List.of(batch == 32)) {
                        fault.commitFirst = committed;
                        fault.armed.set(true);
                        long prior = 2;
                        var change =
                                f.admitCommand(
                                        "owner",
                                        f.ownToken("owner").value(),
                                        pot,
                                        prior,
                                        new PotCommand.UpdatePotDetails(
                                                new Label("unknown-" + committed)));
                        f.verifyCommand(change, "CHANGED", null);
                        await(() -> fault.retained.get() != null);
                        if (committed) {
                            await(
                                    () ->
                                            f.read.queryForObject(
                                                            "select count(*) from read_pot_version"
                                                                + " where pot_id=? and"
                                                                + " pot_version=3",
                                                            Integer.class,
                                                            pot.value())
                                                    == 1);
                            fault.resolve(false);
                            await(() -> capacity.snapshot().worker() == 0);
                            f.drain(ctx);
                            assertEquals(
                                    1,
                                    f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=3",
                                            Integer.class,
                                            pot.value()),
                                    "ACK loss after commit has one immutable effect");
                        } else {
                            assertEquals(
                                    0,
                                    f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=3",
                                            Integer.class,
                                            pot.value()),
                                    "uncommitted effects invisible externally");
                            assertTrue(
                                    capacity.snapshot().worker() >= 1, "UNKNOWN retains capacity");
                            worker.requestDrain();
                            assertEquals(
                                    WorkerView.State.DRAINING,
                                    worker.snapshot().state(),
                                    "UNKNOWN cannot fake STOPPED");
                            assertFalse(
                                    ctx.getBean("readBusiness", HikariDataSource.class).isClosed(),
                                    "UNKNOWN physical loan retained");
                            assertTrue(
                                    f.read.queryForObject(
                                                    "select count(*) from pg_stat_activity where"
                                                        + " pid=? and xact_start is not null",
                                                    Integer.class,
                                                    fault.pid)
                                            == 1,
                                    "unresolved PostgreSQL transaction witness");
                            fault.resolve(false);
                            f.drain(ctx);
                            assertEquals(
                                    0,
                                    f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=3",
                                            Integer.class,
                                            pot.value()),
                                    "no speculative effect after physical rollback");
                        }
                        f.trace.add(
                                "T10 UNKNOWN physical_committed="
                                        + committed
                                        + " PID="
                                        + fault.pid
                                        + " XID="
                                        + fault.xid
                                        + " state="
                                        + worker.snapshot());
                    }
                }
                f.evidence("T10-batch-" + batch);
            }
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, true);
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
