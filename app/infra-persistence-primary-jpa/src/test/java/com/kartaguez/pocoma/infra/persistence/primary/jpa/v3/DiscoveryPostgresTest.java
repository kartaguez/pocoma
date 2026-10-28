package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class DiscoveryPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(ConsumptionLifecyclePostgresFixture.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void init() throws Exception {
        ConsumptionLifecyclePostgresFixture.initialize(PG);
    }

    String method;

    @BeforeEach
    void name(TestInfo info) {
        method = info.getTestMethod().orElseThrow().getName();
    }

    interface Scenario {
        void run(DiscoveryPostgresFixture f) throws Exception;
    }

    void scenario(ConsumableFamily family, boolean real, String variant, Scenario test)
            throws Exception {
        try (var f = new DiscoveryPostgresFixture(PG, family, real)) {
            try {
                var vectors = new ArrayList<UUID>();
                for (int n = 0; n < 5; n++) vectors.add(f.q(n));
                UUID other = DiscoveryPostgresFixture.u(DiscoveryPostgresFixture.P1.get(f.family));
                vectors.add(other);
                var routing = externalPartitions(f.family, vectors);
                for (int n = 0; n < 5; n++) assertEquals(0, routing.get(f.q(n)));
                assertEquals(1, routing.get(other));
                f.trace.add("pre-stimulus independent Python fixture routing=" + routing);
                f.measureLocks =
                        method.equals("i4_b18")
                                || method.equals("i4_p02")
                                || method.equals("i4_p03")
                                || method.equals("i4_x07");
                f.snapshot("before stimulus");
                test.run(f);
                f.trace.add("VARIANT ASSERTIONS SATISFIED " + variant);
            } catch (Throwable e) {
                f.trace.add("VARIANT FAILURE " + variant + " " + e);
                throw e;
            } finally {
                f.save(method, variant);
            }
        }
    }

    void five(String variant, Scenario test) throws Exception {
        for (var family :
                List.of(
                        ConsumableFamily.POT_EVENT,
                        ConsumableFamily.INVITATION_EVENT,
                        ConsumableFamily.IDENTITY_EVENT,
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        ConsumableFamily.IDENTITY_COMPLETION_EVENT))
            scenario(family, false, variant, test);
    }

    void both(String variant, Scenario test) throws Exception {
        for (var family : List.of(ConsumableFamily.COMMAND, ConsumableFamily.IDENTITY_REQUEST))
            scenario(family, family == ConsumableFamily.COMMAND, variant, test);
    }

    void real(String variant, Scenario test) throws Exception {
        for (var family :
                List.of(ConsumableFamily.POT_EVENT, ConsumableFamily.COMMAND_COMPLETION_EVENT))
            scenario(family, true, variant, test);
    }

    List<UUID> four(DiscoveryPostgresFixture f) throws Exception {
        long other =
                switch (f.family) {
                    case POT_EVENT -> 8;
                    case INVITATION_EVENT -> 18;
                    case IDENTITY_EVENT -> 25;
                    case COMMAND_COMPLETION_EVENT -> 10;
                    case IDENTITY_COMPLETION_EVENT -> 5;
                    default -> throw new AssertionError();
                };
        var ids =
                List.of(
                        f.q(0),
                        DiscoveryPostgresFixture.u(DiscoveryPostgresFixture.P1.get(f.family)),
                        f.q(1),
                        DiscoveryPostgresFixture.u(other));
        f.trace.add("expected global positions 1..4=" + ids + " p0={1,3},p1={2,4}");
        f.fixtureEvents(ids);
        return ids;
    }

    void produceReal(DiscoveryPostgresFixture f, int n) throws Exception {
        var w = f.write;
        var ids = input(ConsumableFamily.COMMAND, 0).subList(0, n);
        // Technical durable admission only. Slots are created by real I4, then real I3/I2 execute.
        try (var c = w.admin.getConnection()) {
            c.setAutoCommit(false);
            try (var st =
                    c.prepareStatement(
                            "insert into"
                                + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                + " values(?,'i3','actor','fixture',gen_random_uuid(),0,'CreatePot',?::jsonb)")) {
                for (UUID id : ids) {
                    st.setObject(1, id);
                    st.setString(
                            2,
                            PotCommandJson.encode(
                                    new PotCommand.CreatePot(new Label("I4 real producer"))));
                    st.addBatch();
                }
                st.executeBatch();
            }
            c.commit();
        }
        // A sequential producer fixture retains one actual runtime connection. All delivered
        // D/I3/I2 mechanisms and their one physical JPA owner remain active; no Worker pooling
        // proof.
        long accessStart = System.nanoTime();
        var connection =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        w.source.getConnection(), true);
        f.trace.add(
                "real producer sequential runtime connection accessNs="
                        + (System.nanoTime() - accessStart)
                        + " N="
                        + n);
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(connection);
        emf.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        emf.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(emf.getObject());
            manager.setDataSource(connection);
            manager.setNestedTransactionAllowed(false);
            var owner = new SpringAttemptTransactions(connection, manager);
            var slots = new JdbcConsumptionSlotStore(connection);
            var ownership = new JdbcPartitionOwnershipStore(connection);
            var participant = w.participantFor(connection, owner);
            var capacity = new WorkerCapacity(32, Map.of(w.definition.id(), 32));
            var e =
                    new ConsumptionEngine(
                            w.definition,
                            owner,
                            slots,
                            participant,
                            ownership,
                            w.settings,
                            capacity,
                            c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
            var lease = e.acquirePartition(0).orElseThrow();
            var settings =
                    new DiscoveryEngine.Settings(
                            128, 32, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(10));
            var d =
                    new DiscoveryEngine(
                            w.definition,
                            owner,
                            ownership,
                            new JdbcDiscoveryAdmissionStore(connection, settings),
                            settings,
                            new JdbcCommandDiscoveryReferences(connection, settings));
            var walk = DiscoveryEngine.RequestWalk.fresh();
            for (int offset = 0; offset < n; offset += 32) {
                lease = e.renewPartitions(List.of(lease)).getFirst();
                int end = Math.min(n, offset + 32);
                var progress = d.discoverRequests(lease, walk);
                assertEquals(end - offset, progress.committedAdmissions());
                walk = progress.next();
                var claims = e.acquire(lease, ids.subList(offset, end)).claims();
                assertEquals(end - offset, claims.size());
                for (var claim : claims)
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claim).status());
            }
        } finally {
            emf.destroy();
            connection.destroy();
        }
        assertEquals(
                n,
                w.oracle.queryForObject(
                        "select count(*) from v3_command where status='TERMINAL'", Integer.class));
        assertEquals(
                0, w.oracle.queryForObject("select count(*) from consumption_slot", Integer.class));
        f.trace.add(
                "real Command producer D->I3 acquisition->I2 finalization, technical SQL"
                        + " admission/Identity authority only; positions="
                        + w.oracle.queryForList(
                                "select * from v3_event_position order by family,position"));
    }

    @Test
    void i4_n01() throws Exception {
        five(
                "dense-four",
                f -> {
                    var ids = four(f);
                    for (int p : List.of(0, 1)) {
                        var r = f.engine().discoverEvents(f.lease(p));
                        assertEquals(4, r.checkpoint().orElseThrow());
                        assertEquals(2, r.inserted());
                    }
                    assertEquals(new HashSet<>(ids), f.slotIds());
                    assertEquals(0, f.k(2));
                });
        real(
                "delivered-producer",
                f -> {
                    produceReal(f, 3);
                    var expected =
                            f.write.oracle.queryForList(
                                    "select event_id from v3_event_position where family=? order by"
                                            + " position",
                                    UUID.class,
                                    f.family == ConsumableFamily.POT_EVENT
                                            ? "POT"
                                            : "COMMAND_COMPLETION");
                    assertEquals(3, expected.size());
                    for (int p = 0; p < 4; p++)
                        assertEquals(
                                3,
                                f.engine().discoverEvents(f.lease(p)).checkpoint().orElseThrow());
                    assertEquals(new HashSet<>(expected), f.slotIds());
                });
    }

    @Test
    void i4_n02() throws Exception {
        five(
                "outside-at-cap",
                f -> {
                    f.fixtureEvents(
                            List.of(
                                    DiscoveryPostgresFixture.u(
                                            DiscoveryPostgresFixture.P1.get(f.family))));
                    f.slot(f.q(3), false);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var r = f.engine().discoverEvents(f.lease(0));
                    assertEquals(1, r.checkpoint().orElseThrow());
                    assertEquals(0, r.inserted());
                    assertEquals(Set.of(f.q(3)), f.slotIds());
                });
    }

    @Test
    void i4_n08() throws Exception {
        for (int cut : List.of(1, 2))
            five(
                    "before-insert-" + cut,
                    f -> {
                        var ids = four(f);
                        var lease = f.lease(0);
                        var calls = new AtomicInteger();
                        var store =
                                DiscoveryPostgresFixture.wrap(
                                        DiscoveryAdmissionStore.class,
                                        f.admissions,
                                        (m, a) -> {
                                            if (m.equals("insertInitial")
                                                    && calls.incrementAndGet() == cut)
                                                throw new IllegalStateException(
                                                        "injected insert cut " + cut);
                                        },
                                        (m, a) -> {});
                        var failure =
                                assertThrows(
                                        AttemptTransactions.Failure.class,
                                        () ->
                                                f.engine(store, f.events, null, f.target.tx)
                                                        .discoverEvents(lease));
                        assertEquals(
                                AttemptTransactions.Resolution.ROLLED_BACK, failure.resolution());
                        assertEquals(0, f.k(0));
                        assertTrue(f.slotIds().isEmpty());
                        assertEquals(2, f.engine().discoverEvents(lease).inserted());
                        assertEquals(Set.of(ids.get(0), ids.get(2)), f.slotIds());
                    });
    }

    @Test
    void i4_n09() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "after-last-insert-before-CAS",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    DiscoveryAdmissionStore.class,
                                    f.admissions,
                                    (m, a) -> {
                                        if (m.equals("advanceCheckpoint"))
                                            throw new IllegalStateException("before CAS");
                                    },
                                    (m, a) -> {});
                    assertThrows(
                            AttemptTransactions.Failure.class,
                            () ->
                                    f.engine(store, f.events, null, f.target.tx)
                                            .discoverEvents(lease));
                    assertEquals(0, f.k(0));
                    assertTrue(f.slotIds().isEmpty());
                });
    }

    @Test
    void i4_n10() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "after-CAS-before-commit",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    DiscoveryAdmissionStore.class,
                                    f.admissions,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("advanceCheckpoint")) {
                                            assertEquals(0, f.k(0));
                                            assertTrue(f.slotIds().isEmpty());
                                            throw new IllegalStateException("after CAS");
                                        }
                                    });
                    assertThrows(
                            AttemptTransactions.Failure.class,
                            () ->
                                    f.engine(store, f.events, null, f.target.tx)
                                            .discoverEvents(lease));
                    assertEquals(0, f.k(0));
                    assertTrue(f.slotIds().isEmpty());
                });
    }

    @Test
    void i4_n03() throws Exception {
        five(
                "window-boundaries-1-2-3",
                f -> {
                    var ids = new ArrayList<UUID>();
                    for (long n = 100; n < 400; n++) ids.add(DiscoveryPostgresFixture.u(n));
                    f.fixtureEvents(ids);
                    var lease = f.lease(0);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    1, 32, 32, 1000, Duration.ofMillis(500), Duration.ofSeconds(2));
                    for (int w : List.of(1, 2, 3)) {
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        w,
                                        32,
                                        32,
                                        1000,
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(2));
                        long start = f.k(0);
                        for (int call = 0; call < 10; call++) {
                            var r = f.engine().discoverEvents(lease);
                            assertTrue(r.examined() <= w);
                            assertTrue(r.inserted() <= 32);
                        }
                        assertEquals(start + 10L * w, f.k(0));
                    }
                    while (f.k(0) < 300) f.engine().discoverEvents(lease);
                    var routing = externalPartitions(f.family, ids);
                    var expected = new HashSet<UUID>();
                    routing.forEach(
                            (id, partition) -> {
                                if (partition == 0) expected.add(id);
                            });
                    f.trace.add("independent 300-position expected routing=" + routing);
                    assertEquals(expected, f.slotIds());
                });
    }

    @Test
    void i4_n06() throws Exception {
        realStalePreparation();
        five(
                "same-K-stale",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    var prepared = new CountDownLatch(1);
                    var proceed = new CountDownLatch(1);
                    var events =
                            DiscoveryPostgresFixture.wrap(
                                    NonBurnableReferences.class,
                                    f.events,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("window")) {
                                            prepared.countDown();
                                            assertTrue(proceed.await(8, TimeUnit.SECONDS));
                                        }
                                    });
                    var d2 =
                            f.target.threads.submit(
                                    () ->
                                            f.engine(f.admissions, events, null, f.target.tx)
                                                    .discoverEvents(lease));
                    assertTrue(prepared.await(8, TimeUnit.SECONDS));
                    var d1 = f.engine().discoverEvents(lease);
                    assertEquals(2, d1.inserted());
                    proceed.countDown();
                    var stale = d2.get(8, TimeUnit.SECONDS);
                    assertEquals(DiscoveryEngine.Status.STALE_CHECKPOINT, stale.status());
                    assertEquals(4, stale.checkpoint().orElseThrow());
                    assertEquals(2, f.slotIds().size());
                });
    }

    @Test
    void i4_n07() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "takeover-after-preparation",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    var next = new AtomicReference<PartitionLease>();
                    var events =
                            DiscoveryPostgresFixture.wrap(
                                    NonBurnableReferences.class,
                                    f.events,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("window")) {
                                            next.set(
                                                    f.target
                                                            .threads
                                                            .submit(
                                                                    () -> {
                                                                        f.target.oracle.update(
                                                                                "update"
                                                                                    + " partition_ownership"
                                                                                    + " set owned_until=clock_timestamp()-interval"
                                                                                    + " '1 second'"
                                                                                    + " where"
                                                                                    + " consumption_id=?"
                                                                                    + " and partition_id=0",
                                                                                f.definition
                                                                                        .id()
                                                                                        .value());
                                                                        return f.lease(0);
                                                                    })
                                                            .get(8, TimeUnit.SECONDS));
                                        }
                                    });
                    assertEquals(
                            DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                            f.engine(f.admissions, events, null, f.target.tx)
                                    .discoverEvents(lease)
                                    .status());
                    assertEquals(0, f.k(0));
                    assertTrue(f.slotIds().isEmpty());
                    assertEquals(2, f.engine().discoverEvents(next.get()).inserted());
                });
    }

    @Test
    void i4_n12() throws Exception {
        exactCapAndBatchVariants();
        for (int cap : List.of(1, 2))
            five(
                    "cap-" + cap,
                    f -> {
                        var ids = four(f);
                        var lease = f.lease(0);
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        128,
                                        32,
                                        cap,
                                        cap,
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(2));
                        if (cap == 2)
                            f.slot(f.q(4), false); // stock cap-1, same single remaining place
                        var result = f.engine().discoverEvents(lease);
                        assertEquals(2, result.checkpoint().orElseThrow());
                        assertEquals(1, result.inserted());
                        assertFalse(f.slotIds().contains(ids.get(2)));
                        var stopped = f.engine().discoverEvents(lease);
                        assertEquals(2, stopped.checkpoint().orElseThrow());
                        assertEquals(0, stopped.inserted());
                        f.complete(lease, ids.get(0));
                        assertEquals(
                                4, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                        assertTrue(f.slotIds().contains(ids.get(2)));
                    });
    }

    @Test
    void i4_n13() throws Exception {
        noRelevantIdentityEvents();
        scenario(
                ConsumableFamily.IDENTITY_EVENT,
                false,
                "stable-EUI-filter",
                f -> {
                    four(f);
                    f.write.oracle.update(
                            "update fixture_discovery_event set subject='POCOMA_USER' where"
                                    + " position=1");
                    var r = f.engine().discoverEvents(f.lease(0));
                    assertEquals(4, r.checkpoint().orElseThrow());
                    assertEquals(Set.of(f.q(1)), f.slotIds());
                });
    }

    @Test
    void i4_n16() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "negative-SQL-invariants",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    f.engine().discoverEvents(lease);
                    var before = f.slotIds();
                    for (String sql :
                            List.of(
                                    "update discovery_checkpoint set position=0",
                                    "delete from discovery_checkpoint",
                                    "update consumption_slot set partition_hash=0",
                                    "truncate consumption_slot",
                                    "update consumption_routing_contract set partition_count=4"))
                        assertThrows(RuntimeException.class, () -> f.target.sql.execute(sql), sql);
                    assertEquals(4, f.k(0));
                    assertEquals(before, f.slotIds());
                });
        both(
                "no-burnable-checkpoint",
                f -> {
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    f.target.sql.update(
                                            "insert into discovery_checkpoint values(?,0,1)",
                                            f.definition.id().value()));
                    assertEquals(0, f.k(0));
                });
    }

    @Test
    void i4_n17() throws Exception {
        missingEnvelopeAndSnapshot();
        for (String corruption : List.of("hole", "duplicate", "safe-behind"))
            scenario(
                    ConsumableFamily.POT_EVENT,
                    false,
                    corruption,
                    f -> {
                        four(f);
                        if (corruption.equals("hole"))
                            f.write.oracle.update(
                                    "delete from fixture_discovery_event where position=2");
                        if (corruption.equals("duplicate"))
                            f.write.oracle.execute(
                                    "alter table fixture_discovery_event drop constraint"
                                            + " fixture_discovery_event_id_key;update"
                                            + " fixture_discovery_event set id=(select id from"
                                            + " fixture_discovery_event where position=1) where"
                                            + " position=2");
                        if (corruption.equals("safe-behind"))
                            f.target.oracle.update(
                                    "insert into discovery_checkpoint values(?,0,5)",
                                    f.definition.id().value());
                        long before = f.k(0);
                        assertThrows(
                                RuntimeException.class,
                                () -> f.engine().discoverEvents(f.lease(0)));
                        assertEquals(before, f.k(0));
                        assertTrue(f.slotIds().isEmpty());
                    });
    }

    @Test
    void i4_b01() throws Exception {
        both(
                "pending-admission",
                f -> {
                    f.pending(f.q(0));
                    var r =
                            f.engine()
                                    .discoverRequests(
                                            f.lease(0), DiscoveryEngine.RequestWalk.fresh());
                    assertEquals(1, r.committedAdmissions());
                    assertEquals(Set.of(f.q(0)), f.slotIds());
                    assertEquals(0, f.k(0));
                    assertEquals(
                            "PENDING",
                            f.write.oracle.queryForObject(
                                    "select status from "
                                            + f.requestTable()
                                            + " where "
                                            + f.requestKey()
                                            + "=?",
                                    String.class,
                                    f.q(0)));
                });
    }

    @Test
    void i4_b02() throws Exception {
        both(
                "existing-free-and-claimed",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    var e = f.lifecycle();
                    for (boolean active : List.of(false, true)) {
                        if (active)
                            assertEquals(1, e.acquire(lease, List.of(f.q(0))).claims().size());
                        var before = f.target.oracle.queryForList("select * from consumption_slot");
                        assertEquals(
                                0,
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh())
                                        .committedAdmissions());
                        assertEquals(
                                before,
                                f.target.oracle.queryForList("select * from consumption_slot"));
                    }
                });
    }

    @Test
    void i4_b03() throws Exception {
        both(
                "expired-claim",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    var e = f.lifecycle();
                    var original = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    f.target.oracle.update(
                            "update consumption_slot set claimed_until=clock_timestamp()-interval"
                                    + " '1 second' where consumption_id=?",
                            f.definition.id().value());
                    var before = f.target.oracle.queryForList("select * from consumption_slot");
                    assertEquals(
                            0,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertEquals(
                            before, f.target.oracle.queryForList("select * from consumption_slot"));
                    var next = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    assertNotEquals(original.claim().claimToken(), next.claim().claimToken());
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(next).status());
                });
    }

    @Test
    void i4_b04() throws Exception {
        both(
                "retry-deadline",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    var deadline =
                            f.target
                                    .oracle
                                    .queryForObject(
                                            "update consumption_slot set"
                                                    + " retry_after=clock_timestamp()+interval '1"
                                                    + " second' where consumption_id=? returning"
                                                    + " retry_after",
                                            Timestamp.class,
                                            f.definition.id().value())
                                    .toInstant();
                    var before = f.target.oracle.queryForList("select * from consumption_slot");
                    assertEquals(
                            0,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertEquals(
                            before, f.target.oracle.queryForList("select * from consumption_slot"));
                    assertTrue(f.lifecycle().acquire(lease, List.of(f.q(0))).claims().isEmpty());
                    f.target.await(() -> f.target.now().isAfter(deadline));
                    f.complete(lease, f.q(0));
                });
    }

    @Test
    void i4_b05() throws Exception {
        both(
                "quarantine-restart",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), true);
                    f.target.oracle.update(
                            "update consumption_slot set attempt_count=3 where consumption_id=?",
                            f.definition.id().value());
                    var before = f.target.oracle.queryForList("select * from consumption_slot");
                    var lease = f.lease(0);
                    for (int tour = 0; tour < 3; tour++) {
                        var r =
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh());
                        assertEquals(0, r.committedAdmissions());
                        assertTrue(r.tourFinished());
                        assertEquals(0, f.count());
                        assertEquals(
                                before,
                                f.target.oracle.queryForList("select * from consumption_slot"));
                    }
                });
    }

    @Test
    void i4_b06() throws Exception {
        both(
                "terminal-between-page-and-lock",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    var called = new AtomicBoolean();
                    var source =
                            DiscoveryPostgresFixture.wrap(
                                    BurnableReferences.class,
                                    f.requests,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("pendingPage")
                                                && called.compareAndSet(false, true))
                                            f.target
                                                    .threads
                                                    .submit(
                                                            () -> {
                                                                f.complete(lease, f.q(0));
                                                                return null;
                                                            })
                                                    .get(8, TimeUnit.SECONDS);
                                    });
                    assertEquals(
                            0,
                            f.engine(f.admissions, null, source, f.target.tx)
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertTrue(f.slotIds().isEmpty());
                    assertEquals(
                            "TERMINAL",
                            f.write.oracle.queryForObject(
                                    "select status from " + f.requestTable(), String.class));
                });
    }

    @Test
    void i4_b09() throws Exception {
        nearCapConcurrentRequests();
        both(
                "same-owner-concurrent-Discoveries",
                f -> {
                    f.pending(f.q(0));
                    f.pending(f.q(1));
                    var lease = f.lease(0);
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var first = new AtomicBoolean();
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    DiscoveryAdmissionStore.class,
                                    f.admissions,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("insertInitial")
                                                && first.compareAndSet(false, true)) {
                                            held.countDown();
                                            assertTrue(release.await(8, TimeUnit.SECONDS));
                                        }
                                    });
                    var d =
                            f.target.threads.submit(
                                    () ->
                                            f.engine(store, null, f.requests, f.target.tx)
                                                    .discoverRequests(
                                                            lease,
                                                            DiscoveryEngine.RequestWalk.fresh()));
                    assertTrue(held.await(8, TimeUnit.SECONDS));
                    try {
                        assertEquals(
                                DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh())
                                        .status());
                        assertTrue(f.slotIds().isEmpty());
                        f.locks("D1-held-partition");
                    } finally {
                        release.countDown();
                    }
                    assertEquals(2, d.get(8, TimeUnit.SECONDS).committedAdmissions());
                    assertEquals(
                            0,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertEquals(Set.of(f.q(0), f.q(1)), f.slotIds());
                });
    }

    void arrival(boolean behind) throws Exception {
        both(
                behind ? "arrival-behind" : "arrival-above-U",
                f -> {
                    f.pending(f.q(1));
                    f.pending(f.q(3));
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 1, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var first =
                            f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    assertFalse(first.tourFinished());
                    assertEquals(Optional.of(f.q(3)), first.next().upperInclusive());
                    UUID newId = behind ? f.q(0) : f.q(4);
                    f.pending(newId);
                    var end = f.engine().discoverRequests(lease, first.next());
                    assertTrue(end.tourFinished());
                    assertFalse(f.slotIds().contains(newId));
                    var walk = DiscoveryEngine.RequestWalk.fresh();
                    for (int call = 0; call < 8; call++) {
                        var r = f.engine().discoverRequests(lease, walk);
                        walk = r.next();
                        if (r.tourFinished()) break;
                    }
                    assertTrue(f.slotIds().contains(newId));
                });
    }

    @Test
    void i4_b10() throws Exception {
        arrival(true);
    }

    @Test
    void i4_b11() throws Exception {
        arrival(false);
    }

    @Test
    void i4_b12() throws Exception {
        both(
                "skip-then-free-next-tour",
                f -> {
                    var other =
                            DiscoveryPostgresFixture.u(DiscoveryPostgresFixture.P1.get(f.family));
                    for (var id : List.of(other, f.q(0), f.q(1), f.q(2))) f.pending(id);
                    f.slot(f.q(0), false);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 1, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var walk = DiscoveryEngine.RequestWalk.fresh();
                    try (var locked = f.lockSource(f.q(1))) {
                        for (int call = 0; call < 4; call++) {
                            var r = f.engine().discoverRequests(lease, walk);
                            f.trace.add("first tour " + r);
                            assertEquals(1, r.examined());
                            assertEquals(1, r.advanced());
                            if (call < 3) assertFalse(r.tourFinished());
                            walk = r.next();
                        }
                        assertEquals(Set.of(f.q(0), f.q(2)), f.slotIds());
                        f.locks("skipped-source-still-held");
                        locked.rollback();
                    }
                    for (int call = 0; call < 4; call++) {
                        var r = f.engine().discoverRequests(lease, walk);
                        f.trace.add("free tour " + r);
                        walk = r.next();
                    }
                    assertEquals(Set.of(f.q(0), f.q(1), f.q(2)), f.slotIds());
                });
    }

    @Test
    void i4_b13() throws Exception {
        lockedAtInitialCapacity();
        both(
                "initial-saturation-and-return",
                f -> {
                    for (var id : List.of(f.q(0), f.q(1), f.q(2))) f.pending(id);
                    f.slot(f.q(0), false);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var walk = DiscoveryEngine.RequestWalk.fresh();
                    var stopped = f.engine().discoverRequests(lease, walk);
                    assertEquals(walk, stopped.next());
                    assertEquals(0, stopped.examined());
                    assertEquals(0, stopped.advanced());
                    assertEquals(0, stopped.committedAdmissions());
                    f.complete(lease, f.q(0));
                    var first = f.engine().discoverRequests(lease, walk);
                    assertEquals(1, first.committedAdmissions());
                    f.complete(lease, f.q(1));
                    var last = f.engine().discoverRequests(lease, first.next());
                    assertEquals(1, last.committedAdmissions());
                    assertEquals(Set.of(f.q(2)), f.slotIds());
                });
    }

    @Test
    void i4_b14() throws Exception {
        both(
                "capacity-frontier",
                f -> {
                    for (var id : List.of(f.q(0), f.q(1), f.q(2))) f.pending(id);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var first =
                            f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    assertEquals(2, first.examined());
                    assertEquals(1, first.advanced());
                    assertEquals(1, first.committedAdmissions());
                    assertEquals(Optional.of(f.q(0)), first.next().afterExclusive());
                    f.complete(lease, f.q(0));
                    var second = f.engine().discoverRequests(lease, first.next());
                    assertEquals(1, second.committedAdmissions());
                    assertEquals(Optional.of(f.q(1)), second.next().afterExclusive());
                    f.complete(lease, f.q(1));
                    assertEquals(
                            1,
                            f.engine()
                                    .discoverRequests(lease, second.next())
                                    .committedAdmissions());
                    assertEquals(Set.of(f.q(2)), f.slotIds());
                });
    }

    @Test
    void i4_b15() throws Exception {
        both(
                "takeover-loses-walk",
                f -> {
                    for (var id : List.of(f.q(0), f.q(1), f.q(4))) f.pending(id);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 2, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    try (var locked = f.lockSource(f.q(0))) {
                        var r =
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh());
                        assertEquals(Optional.of(f.q(1)), r.next().afterExclusive());
                        assertFalse(f.slotIds().contains(f.q(0)));
                        locked.rollback();
                    }
                    f.target.oracle.update(
                            "update partition_ownership set owned_until=clock_timestamp()-interval"
                                    + " '1 second' where consumption_id=? and partition_id=0",
                            f.definition.id().value());
                    var next = f.lease(0);
                    assertEquals(
                            DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .status());
                    var walk = DiscoveryEngine.RequestWalk.fresh();
                    for (int call = 0; call < 3; call++) {
                        var r = f.engine().discoverRequests(next, walk);
                        walk = r.next();
                        if (r.tourFinished()) break;
                    }
                    assertEquals(Set.of(f.q(0), f.q(1), f.q(4)), f.slotIds());
                    assertEquals(0, f.k(0));
                });
    }

    @Test
    void i4_b16() throws Exception {
        arrivalsWithSkippedReference();
        both(
                "controlled-finite-arrivals",
                f -> {
                    f.pending(f.q(1));
                    f.pending(f.q(3));
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 1, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var r = f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    for (var id : List.of(f.q(0), f.q(2), f.q(4))) f.pending(id);
                    var walk = r.next();
                    int calls = 0;
                    do {
                        r = f.engine().discoverRequests(lease, walk);
                        f.trace.add("bounded current tour " + r);
                        assertEquals(Optional.of(f.q(3)), walk.upperInclusive());
                        walk = r.next();
                        assertTrue(++calls <= 4);
                    } while (!r.tourFinished());
                    assertEquals(Set.of(f.q(1), f.q(2), f.q(3)), f.slotIds());
                    calls = 0;
                    do {
                        r = f.engine().discoverRequests(lease, walk);
                        f.trace.add("new tour " + r);
                        walk = r.next();
                        assertTrue(++calls <= 6);
                    } while (!r.tourFinished());
                    assertEquals(Set.of(f.q(0), f.q(1), f.q(2), f.q(3), f.q(4)), f.slotIds());
                });
    }

    static final AtomicLong BARRIER_KEYS = new AtomicLong(41000);

    final class PausedFinalizer implements AutoCloseable {
        final DiscoveryPostgresFixture f;
        final Connection control;
        final long key = BARRIER_KEYS.incrementAndGet();
        final int controlPid, pid;
        final Future<ConsumptionEngine.Attempt> future;
        final UUID id;
        boolean released;

        PausedFinalizer(
                DiscoveryPostgresFixture f,
                PartitionLease lease,
                UUID id,
                String cut,
                boolean rollback)
                throws Exception {
            this.f = f;
            this.id = id;
            control = f.write.admin.getConnection();
            try (var st = control.createStatement();
                    var r =
                            st.executeQuery(
                                    "select pg_backend_pid(),pg_advisory_lock(" + key + ")")) {
                r.next();
                controlPid = r.getInt(1);
            }
            String result =
                    f.family == ConsumableFamily.COMMAND
                            ? "v3_command_result"
                            : "fixture_discovery_result";
            String table =
                    cut.equals("after-update")
                            ? f.requestTable()
                            : cut.equals("after-delete") ? "consumption_slot" : result;
            String when =
                    cut.equals("before-FK")
                            ? "before insert"
                            : cut.equals("after-update")
                                    ? "after update"
                                    : cut.equals("after-delete") ? "after delete" : "after insert";
            f.write.oracle.execute(
                    "create function fixture_i4_pause() returns trigger language plpgsql as $$"
                            + " begin perform pg_advisory_xact_lock("
                            + key
                            + "); return coalesce(new,old); end $$;create trigger zz_i4_pause "
                            + when
                            + " on "
                            + table
                            + " for each row when ("
                            + (cut.equals("after-delete")
                                    ? "old.consumable_id"
                                    : "new." + f.requestKey())
                            + "='"
                            + id
                            + "'::uuid) execute function fixture_i4_pause()");
            var participant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID request) {
                            return f.participant
                                    .prepare(request)
                                    .map(
                                            p ->
                                                    new Prepared() {
                                                        public void finalizeIn(SlotCompletion c) {
                                                            p.finalizeIn(c);
                                                            if (rollback)
                                                                throw new CancellationException(
                                                                        "controlled full"
                                                                                + " finalization"
                                                                                + " rollback");
                                                        }

                                                        public void requireCompletionReady() {
                                                            p.requireCompletionReady();
                                                        }
                                                    });
                        }

                        public RecoveryObservation observe(UUID request, RecoveryReadContext c) {
                            return f.participant.observe(request, c);
                        }
                    };
            var e =
                    new ConsumptionEngine(
                            f.definition,
                            f.target.tx,
                            f.target.slots,
                            participant,
                            f.target.ownership,
                            f.target.settings,
                            f.capacity,
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var claim = e.acquire(lease, List.of(id)).claims().getFirst();
            future = f.target.threads.submit(() -> e.execute(claim));
            f.target.await(
                    () ->
                            !f.write
                                    .oracle
                                    .queryForList(
                                            "select pid from pg_stat_activity where"
                                                    + " ?=any(pg_blocking_pids(pid))",
                                            Integer.class,
                                            controlPid)
                                    .isEmpty());
            pid =
                    f.write.oracle.queryForObject(
                            "select pid from pg_stat_activity where ?=any(pg_blocking_pids(pid))",
                            Integer.class,
                            controlPid);
            f.trace.add(
                    "F paused cut="
                            + cut
                            + " pid="
                            + pid
                            + " controller="
                            + controlPid
                            + " rollback="
                            + rollback);
            f.locks("F-paused-" + cut);
        }

        void release() throws Exception {
            if (!released) {
                try (var s = control.createStatement()) {
                    s.execute("select pg_advisory_unlock(" + key + ")");
                }
                released = true;
            }
        }

        ConsumptionEngine.Attempt finish() throws Exception {
            release();
            return future.get(10, TimeUnit.SECONDS);
        }

        public void close() throws Exception {
            release();
            try {
                future.get(10, TimeUnit.SECONDS);
            } finally {
                control.close();
            }
        }
    }

    @Test
    void i4_b07() throws Exception {
        both(
                "F-before-FK-D-holds-source",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128,
                                    32,
                                    32,
                                    64,
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(10));
                    try (var fin = new PausedFinalizer(f, lease, f.q(0), "before-FK", false)) {
                        var held = new CountDownLatch(1);
                        var release = new CountDownLatch(1);
                        var dpid = new AtomicInteger();
                        var source =
                                DiscoveryPostgresFixture.wrap(
                                        BurnableReferences.class,
                                        f.requests,
                                        (m, a) -> {},
                                        (m, a) -> {
                                            if (m.equals("tryProtectPending")) {
                                                dpid.set(
                                                        f.write.sql.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                held.countDown();
                                                assertTrue(release.await(8, TimeUnit.SECONDS));
                                            }
                                        });
                        var d =
                                f.target.threads.submit(
                                        () ->
                                                f.engine(f.admissions, null, source, f.target.tx)
                                                        .discoverRequests(
                                                                lease,
                                                                DiscoveryEngine.RequestWalk
                                                                        .fresh()));
                        assertTrue(held.await(8, TimeUnit.SECONDS));
                        try {
                            fin.release();
                            f.write.blocked(fin.pid);
                            assertTrue(
                                    f.write.oracle.queryForObject(
                                            "select ?=any(pg_blocking_pids(?))",
                                            Boolean.class,
                                            dpid.get(),
                                            fin.pid));
                            f.locks("F-waits-D-source-no-D-slot-wait");
                            assertFalse(d.isDone());
                        } finally {
                            release.countDown();
                        }
                        assertEquals(0, d.get(8, TimeUnit.SECONDS).committedAdmissions());
                        assertEquals(ConsumptionEngine.Status.COMMITTED, fin.finish().status());
                        assertTrue(f.slotIds().isEmpty());
                    }
                });
    }

    @Test
    void i4_b08() throws Exception {
        for (String cut : List.of("after-FK", "after-update", "after-delete"))
            for (boolean rollback : List.of(false, true))
                both(
                        cut + "-" + (rollback ? "rollback" : "commit"),
                        f -> {
                            f.pending(f.q(0));
                            f.slot(f.q(0), false);
                            var lease = f.lease(0);
                            Set<UUID> before = f.slotIds();
                            try (var fin = new PausedFinalizer(f, lease, f.q(0), cut, rollback)) {
                                var r =
                                        f.engine()
                                                .discoverRequests(
                                                        lease, DiscoveryEngine.RequestWalk.fresh());
                                assertEquals(1, r.examined());
                                assertEquals(1, r.advanced());
                                assertEquals(0, r.committedAdmissions());
                                assertFalse(fin.future.isDone());
                                f.locks("D-physically-resolved-while-F-held");
                                var result = fin.finish();
                                assertEquals(
                                        rollback
                                                ? ConsumptionEngine.Status.ROLLED_BACK
                                                : ConsumptionEngine.Status.COMMITTED,
                                        result.status());
                                assertEquals(rollback ? before : Set.of(), f.slotIds());
                                assertEquals(
                                        0,
                                        f.engine()
                                                .discoverRequests(
                                                        lease, DiscoveryEngine.RequestWalk.fresh())
                                                .committedAdmissions());
                            }
                        });
    }

    @Test
    void i4_b18() throws Exception {
        duplicateLockedCost();
        for (boolean rollback : List.of(false, true))
            both(
                    "composite-five-" + (rollback ? "rollback" : "commit"),
                    f -> {
                        for (int i = 0; i < 5; i++) f.pending(f.q(i));
                        f.slot(f.q(1), false);
                        f.slot(f.q(2), true);
                        var lease = f.lease(0);
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        128,
                                        32,
                                        32,
                                        2,
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(10));
                        var quarantine =
                                f.target.oracle.queryForList(
                                        "select * from consumption_slot where consumption_id=? and"
                                                + " consumable_id=?",
                                        f.definition.id().value(),
                                        f.q(2));
                        assertEquals(1, f.count());
                        try (var fin =
                                new PausedFinalizer(f, lease, f.q(1), "after-FK", rollback)) {
                            var checkpoints = new ArrayList<String>();
                            var dpid = new AtomicInteger();
                            var renewed = new AtomicBoolean();
                            var store =
                                    DiscoveryPostgresFixture.wrap(
                                            DiscoveryAdmissionStore.class,
                                            f.admissions,
                                            (m, a) -> {},
                                            (m, a) -> {
                                                if (m.equals("insertInitial")
                                                        || m.equals("contains")) {
                                                    dpid.set(
                                                            f.target.sql.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    f.trace.add(
                                                            "D operation "
                                                                    + m
                                                                    + " "
                                                                    + Arrays.toString(a)
                                                                    + " pid/xid/time="
                                                                    + f.target.sql.queryForMap(
                                                                            "select"
                                                                                + " pg_backend_pid(),pg_current_xact_id()::text,clock_timestamp()"));
                                                    assertFalse(fin.future.isDone());
                                                    f.locks("D-" + m);
                                                    checkpoints.add(m + ":" + a[a.length - 1]);
                                                    UUID encountered = (UUID) a[a.length - 1];
                                                    try (var probe =
                                                            f.write.admin.getConnection()) {
                                                        probe.setAutoCommit(false);
                                                        try (var query =
                                                                probe.prepareStatement(
                                                                        "select "
                                                                                + f.requestKey()
                                                                                + " from "
                                                                                + f.requestTable()
                                                                                + " where "
                                                                                + f.requestKey()
                                                                                + "=? for update"
                                                                                + " skip locked")) {
                                                            query.setObject(1, encountered);
                                                            try (var rows = query.executeQuery()) {
                                                                assertFalse(
                                                                        rows.next(),
                                                                        "D must retain source lock"
                                                                            + " through physical"
                                                                            + " resolution");
                                                            }
                                                        }
                                                        probe.rollback();
                                                    }
                                                    if (m.equals("contains")
                                                            && encountered.equals(f.q(2))
                                                            && renewed.compareAndSet(false, true)) {
                                                        f.target
                                                                .threads
                                                                .submit(
                                                                        () -> {
                                                                            UUID witness =
                                                                                    DiscoveryPostgresFixture
                                                                                            .u(
                                                                                                    DiscoveryPostgresFixture
                                                                                                            .P1
                                                                                                            .get(
                                                                                                                    f.family));
                                                                            f.pending(witness);
                                                                            f.slot(witness, false);
                                                                            var independentLease =
                                                                                    f.lease(1);
                                                                            var e = f.lifecycle();
                                                                            var claim =
                                                                                    e.acquire(
                                                                                                    independentLease,
                                                                                                    List
                                                                                                            .of(
                                                                                                                    witness))
                                                                                            .claims()
                                                                                            .getFirst();
                                                                            assertEquals(
                                                                                    1,
                                                                                    e.renewClaims(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    claim
                                                                                                                            .claim()))
                                                                                            .size());
                                                                            assertEquals(
                                                                                    1,
                                                                                    e.renewPartitions(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    independentLease))
                                                                                            .size());
                                                                            assertTrue(
                                                                                    e.renewPartitions(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    lease))
                                                                                            .isEmpty());
                                                                            f.trace.add(
                                                                                    "R independent"
                                                                                        + " claim"
                                                                                        + " and p1"
                                                                                        + " lease"
                                                                                        + " committed"
                                                                                        + " while"
                                                                                        + " D/F held;"
                                                                                        + " p0 lease"
                                                                                        + " correctly"
                                                                                        + " skipped");
                                                                            return null;
                                                                        })
                                                                .get(8, TimeUnit.SECONDS);
                                                    }
                                                }
                                            });
                            var r =
                                    f.engine(store, null, f.requests, f.target.tx)
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh());
                            assertEquals(DiscoveryEngine.Status.CAPACITY, r.status());
                            assertEquals(4, r.examined());
                            assertEquals(3, r.advanced());
                            assertEquals(1, r.committedAdmissions());
                            assertEquals(Optional.of(f.q(2)), r.next().afterExclusive());
                            assertEquals(Optional.of(f.q(4)), r.next().upperInclusive());
                            assertFalse(r.tourFinished());
                            assertFalse(
                                    checkpoints.stream()
                                            .anyMatch(v -> v.endsWith(f.q(4).toString())));
                            assertEquals(
                                    Set.of(f.q(0), f.q(1), f.q(2)),
                                    new HashSet<>(
                                            f.target.oracle.queryForList(
                                                    "select consumable_id from consumption_slot"
                                                            + " where consumption_id=? and"
                                                            + " partition_id=0",
                                                    UUID.class,
                                                    f.definition.id().value())));
                            assertEquals(
                                    quarantine,
                                    f.target.oracle.queryForList(
                                            "select * from consumption_slot where consumption_id=?"
                                                    + " and consumable_id=?",
                                            f.definition.id().value(),
                                            f.q(2)));
                            assertFalse(fin.future.isDone());
                            assertEquals(
                                    0,
                                    f.write.oracle.queryForObject(
                                            "select count(*) from pg_locks where pid=? and"
                                                    + " locktype='transactionid' and granted",
                                            Integer.class,
                                            dpid.get()));
                            var finalResult = fin.finish();
                            assertEquals(
                                    rollback
                                            ? ConsumptionEngine.Status.ROLLED_BACK
                                            : ConsumptionEngine.Status.COMMITTED,
                                    finalResult.status());
                            if (rollback) f.complete(lease, f.q(0));
                            var next = f.engine().discoverRequests(lease, r.next());
                            assertEquals(1, next.committedAdmissions());
                            assertEquals(Optional.of(f.q(3)), next.next().afterExclusive());
                            f.complete(lease, f.q(3));
                            var last = f.engine().discoverRequests(lease, next.next());
                            assertEquals(1, last.committedAdmissions());
                            assertTrue(last.tourFinished());
                            f.complete(lease, f.q(4));
                            var revisit =
                                    f.engine()
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh());
                            assertEquals(0, revisit.committedAdmissions());
                            assertTrue(f.slotIds().contains(f.q(2)));
                            if (rollback) assertTrue(f.slotIds().contains(f.q(1)));
                            else assertFalse(f.slotIds().contains(f.q(1)));
                            f.trace.add(
                                    "B18 confirmed progress="
                                            + r
                                            + " resume="
                                            + next
                                            + " last="
                                            + last
                                            + " finalizer="
                                            + finalResult.status());
                        }
                    });
    }

    void wire(
            DiscoveryPostgresFixture f,
            PartitionLease lease,
            CommitAcknowledgementProxy.Fault fault)
            throws Exception {
        boolean realEvents = !f.burnable() && f.events instanceof JdbcEventDiscoveryReferences;
        List<UUID> published =
                realEvents
                        ? f.write.oracle.queryForList(
                                "select event_id from v3_event_position where family=? order by"
                                        + " position",
                                UUID.class,
                                f.family == ConsumableFamily.POT_EVENT
                                        ? "POT"
                                        : "COMMAND_COMPLETION")
                        : List.of();
        Set<UUID> expected = f.burnable() || !realEvents ? Set.of(f.q(0), f.q(1)) : new HashSet<>();
        if (realEvents)
            for (var entry : externalPartitions(f.family, published).entrySet())
                if (entry.getValue() == lease.partitionId()) expected.add(entry.getKey());
        long high = realEvents ? published.size() : 4;
        f.trace.add(
                "wire expected before stimulus: ids="
                        + expected
                        + " H="
                        + high
                        + " p="
                        + lease.partitionId());
        try (var proxy = new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
            var source =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://"
                                    + PG.getHost()
                                    + ":"
                                    + proxy.localPort()
                                    + "/"
                                    + f.target.database
                                    + "?currentSchema="
                                    + f.target.schema
                                    + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                            f.target.runtimeRole,
                            "i3-fixture");
            LocalContainerEntityManagerFactoryBean emf = null;
            org.springframework.transaction.PlatformTransactionManager manager;
            if (f.burnable()) {
                emf = new LocalContainerEntityManagerFactoryBean();
                emf.setDataSource(source);
                emf.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
                emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
                emf.afterPropertiesSet();
                var jpa = new JpaTransactionManager(emf.getObject());
                jpa.setDataSource(source);
                jpa.setNestedTransactionAllowed(false);
                manager = jpa;
            } else manager = ConsumptionLifecyclePostgresFixture.jdbcManager(source);
            try {
                var owner = new SpringAttemptTransactions(source, manager);
                var sql = new JdbcTemplate(source);
                AttemptTransactions tx =
                        new AttemptTransactions() {
                            public <T> T read(
                                    java.util.function.Function<RecoveryReadContext, T> action) {
                                return owner.read(action);
                            }

                            public <T> T write(java.util.function.Supplier<T> action) {
                                proxy.armed.set(fault);
                                return owner.write(action);
                            }

                            public void poison(Throwable t) {
                                owner.poison(t);
                            }

                            public void requirePublicationClosed() {
                                owner.requirePublicationClosed();
                            }
                        };
                BurnableReferences requests =
                        f.burnable()
                                ? f.family == ConsumableFamily.COMMAND
                                        ? new JdbcCommandDiscoveryReferences(source, f.settings)
                                        : new BurnableReferences() {
                                            public ConsumableFamily family() {
                                                return f.family;
                                            }

                                            public Optional<UUID> upperPending(
                                                    RecoveryReadContext c) {
                                                SpringAttemptTransactions.requireRead(source, c);
                                                return sql
                                                        .queryForList(
                                                                "select id from"
                                                                    + " fixture_discovery_request"
                                                                    + " where status='PENDING'"
                                                                    + " order by id desc limit 1",
                                                                UUID.class)
                                                        .stream()
                                                        .findFirst();
                                            }

                                            public List<UUID> pendingPage(
                                                    Optional<UUID> after,
                                                    UUID upper,
                                                    int size,
                                                    RecoveryReadContext c) {
                                                SpringAttemptTransactions.requireRead(source, c);
                                                return sql.queryForList(
                                                        "select id from fixture_discovery_request"
                                                            + " where status='PENDING' and id<=?"
                                                            + " order by id limit ?",
                                                        UUID.class,
                                                        upper,
                                                        size);
                                            }

                                            public boolean tryProtectPending(UUID id) {
                                                return SpringAttemptTransactions.guarded(
                                                        source,
                                                        () ->
                                                                !sql.queryForList(
                                                                                "select id from"
                                                                                    + " fixture_discovery_request"
                                                                                    + " where id=?"
                                                                                    + " and status='PENDING'"
                                                                                    + " for update"
                                                                                    + " skip"
                                                                                    + " locked",
                                                                                id)
                                                                        .isEmpty());
                                            }
                                        }
                                : null;
                var admissions = new JdbcDiscoveryAdmissionStore(source, f.settings);
                var ownership = new JdbcPartitionOwnershipStore(source);
                var d =
                        f.burnable()
                                ? new DiscoveryEngine(
                                        f.definition,
                                        tx,
                                        ownership,
                                        admissions,
                                        f.settings,
                                        requests)
                                : new DiscoveryEngine(
                                        f.definition,
                                        tx,
                                        ownership,
                                        admissions,
                                        f.settings,
                                        f.write.tx,
                                        f.events,
                                        r -> true);
                var failure =
                        assertThrows(
                                AttemptTransactions.Failure.class,
                                () -> {
                                    if (f.burnable())
                                        d.discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh());
                                    else d.discoverEvents(lease);
                                });
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, failure.resolution());
                assertEquals(1, proxy.intercepted.get());
                assertEquals(
                        fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0,
                        proxy.committedReplies.get());
                f.target.await(
                        () ->
                                f.target.oracle.queryForObject(
                                                "select count(*) from pg_stat_activity where"
                                                    + " datname=current_database() and state='idle"
                                                    + " in transaction'",
                                                Integer.class)
                                        == 0);
                boolean committed = fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
                assertEquals(committed ? expected : Set.of(), f.slotIds());
                if (!f.burnable()) assertEquals(committed ? high : 0, f.k(lease.partitionId()));
                int inserted =
                        f.burnable()
                                ? f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh())
                                        .committedAdmissions()
                                : f.engine().discoverEvents(lease).inserted();
                assertEquals(committed ? 0 : expected.size(), inserted);
                assertEquals(expected, f.slotIds());
                f.trace.add(
                        "WIRE "
                                + fault
                                + " intercepted="
                                + proxy.intercepted
                                + " serverCommitReplies="
                                + proxy.committedReplies
                                + " historicalResolution=UNKNOWN physical durable="
                                + (committed ? "COMMITTED" : "ROLLED_BACK")
                                + " retry inserted="
                                + inserted);
            } finally {
                if (emf != null) emf.destroy();
            }
        }
    }

    @Test
    void i4_n11() throws Exception {
        realWireEvents();
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT)) {
            five(
                    "wire-" + fault,
                    f -> {
                        four(f);
                        wire(f, f.lease(0), fault);
                    });
        }
    }

    @Test
    void i4_b17() throws Exception {
        for (int cut : List.of(1, 2))
            both(
                    "rollback-after-insert-" + cut,
                    f -> {
                        f.pending(f.q(0));
                        f.pending(f.q(1));
                        var lease = f.lease(0);
                        var calls = new AtomicInteger();
                        var store =
                                DiscoveryPostgresFixture.wrap(
                                        DiscoveryAdmissionStore.class,
                                        f.admissions,
                                        (m, a) -> {},
                                        (m, a) -> {
                                            if (m.equals("insertInitial")
                                                    && calls.incrementAndGet() == cut)
                                                throw new IllegalStateException(
                                                        "cut after insert " + cut);
                                        });
                        var failure =
                                assertThrows(
                                        AttemptTransactions.Failure.class,
                                        () ->
                                                f.engine(store, null, f.requests, f.target.tx)
                                                        .discoverRequests(
                                                                lease,
                                                                DiscoveryEngine.RequestWalk
                                                                        .fresh()));
                        assertEquals(
                                AttemptTransactions.Resolution.ROLLED_BACK, failure.resolution());
                        assertTrue(f.slotIds().isEmpty());
                        assertEquals(
                                2,
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh())
                                        .committedAdmissions());
                    });
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            both(
                    "wire-" + fault,
                    f -> {
                        f.pending(f.q(0));
                        f.pending(f.q(1));
                        wire(f, f.lease(0), fault);
                    });
    }

    @Test
    void i4_n14() throws Exception {
        realCompletionAndAck();
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "completion-rollback-then-commit",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    f.engine().discoverEvents(lease);
                    var bad =
                            new ConsumptionParticipant() {
                                public Optional<Prepared> prepare(UUID id) {
                                    return f.participant
                                            .prepare(id)
                                            .map(
                                                    p ->
                                                            new Prepared() {
                                                                public void finalizeIn(
                                                                        SlotCompletion c) {
                                                                    p.finalizeIn(c);
                                                                    throw new CancellationException(
                                                                            "cut before commit");
                                                                }

                                                                public void
                                                                        requireCompletionReady() {
                                                                    p.requireCompletionReady();
                                                                }
                                                            });
                                }

                                public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                                    return f.participant.observe(id, c);
                                }
                            };
                    var e =
                            new ConsumptionEngine(
                                    f.definition,
                                    f.target.tx,
                                    f.target.slots,
                                    bad,
                                    f.target.ownership,
                                    f.target.settings,
                                    f.capacity,
                                    c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                    var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    assertEquals(ConsumptionEngine.Status.ROLLED_BACK, e.execute(claim).status());
                    assertTrue(f.slotIds().contains(f.q(0)));
                    assertEquals(0, f.target.effects(f.q(0)));
                    f.target.oracle.update(
                            "update consumption_slot set claimed_until=clock_timestamp()-interval"
                                    + " '1 second' where consumption_id=? and consumable_id=?",
                            f.definition.id().value(),
                            f.q(0));
                    f.complete(lease, f.q(0));
                    assertEquals(1, f.target.effects(f.q(0)));
                    assertEquals(0, f.engine().discoverEvents(lease).inserted());
                    assertFalse(f.slotIds().contains(f.q(0)));
                    assertEquals(4, f.k(0));
                });
    }

    @Test
    void i4_n15() throws Exception {
        real(
                "independent-consumption",
                f -> {
                    produceReal(f, 2);
                    var old = f.lease(0);
                    f.engine().discoverEvents(old);
                    long before = f.k(0);
                    var beforeIds = f.slotIds();
                    UUID id = UUID.fromString("09000000-0000-0000-0000-000000000004");
                    f.target.oracle.update(
                            "insert into consumption_routing_contract values(?,?,'sha256-v1',4)",
                            id,
                            f.family.name());
                    for (int p = 0; p < 4; p++)
                        f.target.oracle.update(
                                "insert into partition_ownership(consumption_id,partition_id)"
                                        + " values(?,?)",
                                id,
                                p);
                    var d =
                            new ConsumptionDefinition(
                                    new ConsumptionId(id),
                                    f.family,
                                    ConsumptionDefinition.Authority.READ);
                    var lease =
                            f.target.technical(
                                    () ->
                                            f.target
                                                    .ownership
                                                    .acquire(
                                                            d,
                                                            0,
                                                            UUID.randomUUID(),
                                                            Duration.ofSeconds(30))
                                                    .orElseThrow());
                    var engine =
                            new DiscoveryEngine(
                                    d,
                                    f.target.tx,
                                    f.target.ownership,
                                    f.admissions,
                                    f.settings,
                                    f.write.tx,
                                    f.events,
                                    r -> true);
                    assertEquals(2, engine.discoverEvents(lease).checkpoint().orElseThrow());
                    assertEquals(before, f.k(0));
                    assertEquals(beforeIds, f.slotIds());
                    assertEquals(
                            2,
                            f.target.oracle.queryForObject(
                                    "select position from discovery_checkpoint where"
                                            + " consumption_id=? and partition_id=0",
                                    Integer.class,
                                    id));
                });
    }

    void densePublication(boolean rollback) throws Exception {
        five(
                "publisher-" + (rollback ? "rollback" : "commit"),
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    try (var first = f.write.admin.getConnection()) {
                        first.setAutoCommit(false);
                        try (var st = first.createStatement()) {
                            st.execute(
                                    "update fixture_discovery_counter set position=5;insert into"
                                        + " fixture_discovery_event"
                                        + " values(5,'00000000-0000-0000-0000-000000000064','fixture')");
                        }
                        var secondPid = new AtomicInteger();
                        var entered = new CountDownLatch(1);
                        var second =
                                f.write.threads.submit(
                                        () -> {
                                            try (var c = f.write.admin.getConnection()) {
                                                c.setAutoCommit(false);
                                                try (var st = c.createStatement();
                                                        var rs =
                                                                st.executeQuery(
                                                                        "select"
                                                                            + " pg_backend_pid()")) {
                                                    rs.next();
                                                    secondPid.set(rs.getInt(1));
                                                    entered.countDown();
                                                }
                                                long next;
                                                try (var st = c.createStatement();
                                                        var rs =
                                                                st.executeQuery(
                                                                        "update"
                                                                            + " fixture_discovery_counter"
                                                                            + " set position=position+1"
                                                                            + " returning"
                                                                            + " position")) {
                                                    rs.next();
                                                    next = rs.getLong(1);
                                                }
                                                try (var st =
                                                        c.prepareStatement(
                                                                "insert into"
                                                                    + " fixture_discovery_event"
                                                                    + " values(?,'00000000-0000-0000-0000-000000000065','fixture')")) {
                                                    st.setLong(1, next);
                                                    st.executeUpdate();
                                                }
                                                c.commit();
                                                return next;
                                            }
                                        });
                        assertTrue(entered.await(8, TimeUnit.SECONDS));
                        f.write.blocked(secondPid.get());
                        f.locks("T2 waits real fixture counter");
                        assertEquals(
                                4, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                        if (rollback) first.rollback();
                        else first.commit();
                        assertEquals(rollback ? 5 : 6, second.get(8, TimeUnit.SECONDS));
                        assertEquals(
                                rollback ? 5 : 6,
                                f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                        assertEquals(
                                rollback ? 5 : 6,
                                f.write.oracle.queryForObject(
                                        "select count(*) from fixture_discovery_event",
                                        Integer.class));
                    }
                });
        real(
                "delivered-publisher-" + (rollback ? "rollback" : "commit"),
                f -> {
                    var w = f.write;
                    produceReal(f, 4);
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    // Two distinct admitted EUI occurrences and Pots, while sharing the delivered
                    // counters.
                    var authority =
                            new PotActorAuthority() {
                                public Witness observe(PotCommand c) {
                                    return new Witness(1, Optional.of(new UserId(new UUID(0, 1))));
                                }

                                public boolean protect(PotCommand c, Witness observed) {
                                    w.sql.queryForList(
                                            "select token from fixture_identity where issuer=? and"
                                                    + " subject=? for update",
                                            c.requester().issuer(),
                                            c.requester().subject());
                                    return observe(c).equals(observed);
                                }
                            };
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    PotCommandStore.class,
                                    new JdbcPotCommandStore(w.source, w.tx),
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("publish")
                                                && ((PotCommand) a[0])
                                                        .requester()
                                                        .subject()
                                                        .equals("dense-a")) {
                                            held.countDown();
                                            assertTrue(release.await(8, TimeUnit.SECONDS));
                                            if (rollback)
                                                throw new CancellationException(
                                                        "publisher rollback");
                                        }
                                    });
                    var participant =
                            new ExecutePotCommand(
                                    store,
                                    new JdbcPotCommandObservation(w.source),
                                    authority,
                                    new JdbcPotHistoryAdapter(w.source),
                                    new JdbcPotRevisionAdapter(w.source),
                                    Clock.systemUTC());
                    var e =
                            new ConsumptionEngine(
                                    w.definition,
                                    w.tx,
                                    w.slots,
                                    participant,
                                    w.ownership,
                                    w.settings,
                                    new WorkerCapacity(4, Map.of(w.definition.id(), 4)),
                                    c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                    var claims = new ArrayList<ConsumptionEngine.AcquiredClaim>();
                    for (int n = 0; n < 2; n++) {
                        String actor = n == 0 ? "dense-a" : "dense-b";
                        UUID id = new UUID(4, n + 1);
                        UUID pot = new UUID(5, n + 1);
                        w.oracle.update(
                                "insert into fixture_identity"
                                    + " values('i3',?,'00000000-0000-0000-0000-000000000001','fixture')",
                                actor);
                        w.oracle.update(
                                "insert into"
                                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                    + " values(?,'i3',?,'fixture',?,0,'CreatePot',?::jsonb)",
                                id,
                                actor,
                                pot,
                                PotCommandJson.encode(
                                        new PotCommand.CreatePot(new Label("Dense I4"))));
                        int p =
                                w.oracle.queryForObject(
                                        "select"
                                            + " ((consumption_hash_v1('COMMAND',?)::bigint%4+4)%4)::int",
                                        Integer.class, id);
                        w.oracle.update(
                                "insert into"
                                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                    + " select ?,?,consumption_hash_v1('COMMAND',?),?",
                                w.definition.id().value(),
                                id,
                                id,
                                p);
                        var row =
                                w.oracle.queryForList(
                                        "select owner_token,owned_until from partition_ownership"
                                                + " where consumption_id=? and partition_id=? and"
                                                + " owned_until>clock_timestamp()",
                                        w.definition.id().value(),
                                        p);
                        PartitionLease l =
                                row.isEmpty()
                                        ? e.acquirePartition(p).orElseThrow()
                                        : new PartitionLease(
                                                w.definition.id(),
                                                p,
                                                (UUID) row.getFirst().get("owner_token"),
                                                ((Timestamp) row.getFirst().get("owned_until"))
                                                        .toInstant());
                        claims.add(e.acquire(l, List.of(id)).claims().getFirst());
                    }
                    var first = w.threads.submit(() -> e.execute(claims.get(0)));
                    assertTrue(held.await(8, TimeUnit.SECONDS));
                    var second = w.threads.submit(() -> e.execute(claims.get(1)));
                    w.await(
                            () ->
                                    !w.oracle
                                            .queryForList(
                                                    "select pid from pg_stat_activity where"
                                                        + " datname=current_database() and"
                                                        + " wait_event_type='Lock' and query like"
                                                        + " '%v3_event_counter%'",
                                                    Integer.class)
                                            .isEmpty());
                    int blocked =
                            w.oracle.queryForObject(
                                    "select pid from pg_stat_activity where"
                                        + " datname=current_database() and wait_event_type='Lock'"
                                        + " and query like '%v3_event_counter%'",
                                    Integer.class);
                    w.blocked(blocked);
                    f.locks("real T2 counter wait");
                    var lease = f.lease(0);
                    assertEquals(4, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                    release.countDown();
                    assertEquals(
                            rollback
                                    ? ConsumptionEngine.Status.ROLLED_BACK
                                    : ConsumptionEngine.Status.COMMITTED,
                            first.get(8, TimeUnit.SECONDS).status());
                    assertEquals(
                            ConsumptionEngine.Status.COMMITTED,
                            second.get(8, TimeUnit.SECONDS).status());
                    assertEquals(
                            rollback ? 5 : 6,
                            f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                });
    }

    @Test
    void i4_n04() throws Exception {
        densePublication(false);
    }

    @Test
    void i4_n05() throws Exception {
        densePublication(true);
    }

    @Test
    void i4_n18() throws Exception {
        checkpointRelationWait();
        for (boolean timeout : List.of(false, true))
            scenario(
                    ConsumableFamily.POT_EVENT,
                    false,
                    timeout ? "relation-timeout" : "post-wait-expiry",
                    f -> {
                        four(f);
                        var lease = f.lease(0);
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        128,
                                        32,
                                        32,
                                        64,
                                        timeout ? Duration.ofMillis(200) : Duration.ofSeconds(5),
                                        Duration.ofSeconds(10));
                        var deadline =
                                f.target
                                        .oracle
                                        .queryForObject(
                                                "update partition_ownership set"
                                                    + " owned_until=clock_timestamp()+interval '1"
                                                    + " second' where consumption_id=? and"
                                                    + " partition_id=0 returning owned_until",
                                                Timestamp.class,
                                                f.definition.id().value())
                                        .toInstant();
                        var entered = new CountDownLatch(1);
                        var blocker = new AtomicReference<Connection>();
                        var pid = new AtomicInteger();
                        var store =
                                DiscoveryPostgresFixture.wrap(
                                        DiscoveryAdmissionStore.class,
                                        f.admissions,
                                        (m, a) -> {
                                            if (m.equals("insertInitial")
                                                    && blocker.get() == null) {
                                                var c = f.target.admin.getConnection();
                                                c.setAutoCommit(false);
                                                try (var st = c.createStatement()) {
                                                    st.execute(
                                                            "lock table consumption_slot in share"
                                                                    + " mode");
                                                }
                                                blocker.set(c);
                                                pid.set(
                                                        f.target.sql.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                entered.countDown();
                                            }
                                        },
                                        (m, a) -> {});
                        var future =
                                f.target.threads.submit(
                                        () ->
                                                assertThrows(
                                                        AttemptTransactions.Failure.class,
                                                        () ->
                                                                f.engine(
                                                                                store,
                                                                                f.events,
                                                                                null,
                                                                                f.target.tx)
                                                                        .discoverEvents(lease)));
                        assertTrue(entered.await(8, TimeUnit.SECONDS));
                        try {
                            f.target.blocked(pid.get());
                            f.locks("D relation wait before INSERT");
                            if (!timeout) f.target.await(() -> f.target.now().isAfter(deadline));
                            if (timeout) {
                                var error = future.get(8, TimeUnit.SECONDS);
                                assertEquals(
                                        AttemptTransactions.Resolution.ROLLED_BACK,
                                        error.resolution());
                            } else {
                                blocker.get().rollback();
                                var error = future.get(8, TimeUnit.SECONDS);
                                assertEquals(
                                        AttemptTransactions.Resolution.ROLLED_BACK,
                                        error.resolution());
                            }
                        } finally {
                            if (blocker.get() != null) {
                                blocker.get().rollback();
                                blocker.get().close();
                            }
                        }
                        assertEquals(0, f.k(0));
                        assertTrue(f.slotIds().isEmpty());
                        f.trace.add(
                                "expiry="
                                        + deadline
                                        + " physical rollback confirmed, external K=0 slots=0"
                                        + " DBnow="
                                        + f.target.now());
                    });
    }

    @Test
    void i4_x01() throws Exception {
        both(
                "old-claim-after-takeover",
                f -> {
                    f.pending(f.q(0));
                    var lease = f.lease(0);
                    f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    var e = f.lifecycle();
                    var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    f.target.oracle.update(
                            "update partition_ownership set owned_until=clock_timestamp()-interval"
                                    + " '1 second' where consumption_id=? and partition_id=0",
                            f.definition.id().value());
                    var newLease = f.lease(0);
                    f.pending(f.q(1));
                    assertEquals(
                            DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .status());
                    assertEquals(1, e.renewClaims(List.of(claim.claim())).size());
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claim).status());
                    assertEquals(
                            1,
                            f.engine()
                                    .discoverRequests(newLease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertEquals(Set.of(f.q(1)), f.slotIds());
                });
    }

    @Test
    void i4_x02() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "expired-lease-discovery-refused-existing-claim-finishes",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    f.engine().discoverEvents(lease);
                    var e = f.lifecycle();
                    var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    f.target.oracle.update(
                            "update partition_ownership set owned_until=clock_timestamp()-interval"
                                    + " '1 second' where consumption_id=? and partition_id=0",
                            f.definition.id().value());
                    assertEquals(
                            DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                            f.engine().discoverEvents(lease).status());
                    assertEquals(1, e.renewClaims(List.of(claim.claim())).size());
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claim).status());
                    assertEquals(4, f.k(0));
                    assertFalse(f.slotIds().contains(f.q(0)));
                });
    }

    @Test
    void i4_x03() throws Exception {
        both(
                "administrative-reactivation-over-cap",
                f -> {
                    f.pending(f.q(0));
                    f.pending(f.q(1));
                    f.slot(f.q(0), true);
                    f.slot(f.q(1), false);
                    var lease = f.lease(0);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(2));
                    assertEquals(1, f.count());
                    f.target.script(
                            "reactivate-l2-slot.sql",
                            f.target.adminRole,
                            Map.of(
                                    "consumption_id",
                                    f.definition.id().value().toString(),
                                    "consumable_id",
                                    f.q(0).toString()));
                    assertEquals(2, f.count());
                    assertEquals(
                            DiscoveryEngine.Status.CAPACITY,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .status());
                    f.complete(lease, f.q(1));
                    assertEquals(
                            DiscoveryEngine.Status.CAPACITY,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .status());
                    f.complete(lease, f.q(0));
                    assertEquals(0, f.count());
                });
    }

    @Test
    void i4_x04() throws Exception {
        both(
                "Discovery-held-independent-renewals",
                f -> {
                    f.pending(f.q(0));
                    f.pending(f.q(1));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    var e = f.lifecycle();
                    var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128,
                                    32,
                                    32,
                                    64,
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(10));
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    DiscoveryAdmissionStore.class,
                                    f.admissions,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("liveSlots")) {
                                            held.countDown();
                                            assertTrue(release.await(8, TimeUnit.SECONDS));
                                        }
                                    });
                    var d =
                            f.target.threads.submit(
                                    () ->
                                            f.engine(store, null, f.requests, f.target.tx)
                                                    .discoverRequests(
                                                            lease,
                                                            DiscoveryEngine.RequestWalk.fresh()));
                    assertTrue(held.await(8, TimeUnit.SECONDS));
                    try {
                        assertEquals(1, e.renewClaims(List.of(claim.claim())).size());
                        assertTrue(e.renewPartitions(List.of(lease)).isEmpty());
                        f.locks("claim renewal committed while Discovery partition held");
                    } finally {
                        release.countDown();
                    }
                    assertEquals(1, d.get(8, TimeUnit.SECONDS).committedAdmissions());
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claim).status());
                });
    }

    @Test
    void i4_x05() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "READ-xid-and-WRITE-read-only",
                f -> {
                    four(f);
                    var sourceBefore =
                            f.write.oracle.queryForList(
                                    "select * from fixture_discovery_event order by position");
                    f.target.oracle.execute(
                            "create table fixture_discovery_units(op text,pid integer,xid"
                                + " xid8);create function fixture_discovery_unit() returns trigger"
                                + " language plpgsql as $$ begin insert into"
                                + " fixture_discovery_units"
                                + " values(TG_TABLE_NAME,pg_backend_pid(),pg_current_xact_id());return"
                                + " new;end $$;create trigger fixture_trace_slot after insert on"
                                + " consumption_slot for each row execute function"
                                + " fixture_discovery_unit();create trigger fixture_trace_k after"
                                + " insert or update on discovery_checkpoint for each row execute"
                                + " function fixture_discovery_unit();grant insert on"
                                + " fixture_discovery_units to "
                                    + f.target.runtimeRole);
                    var lease = f.lease(0);
                    f.engine().discoverEvents(lease);
                    var units =
                            f.target.oracle.queryForList("select * from fixture_discovery_units");
                    assertEquals(3, units.size());
                    assertEquals(1, units.stream().map(r -> r.get("pid")).distinct().count());
                    assertEquals(1, units.stream().map(r -> r.get("xid")).distinct().count());
                    f.trace.add("physical READ slot/K units=" + units);
                    assertEquals(
                            sourceBefore,
                            f.write.oracle.queryForList(
                                    "select * from fixture_discovery_event order by position"));
                    f.complete(lease, f.q(0));
                    assertEquals(1, f.target.effects(f.q(0)));
                    assertEquals(4, f.k(0));
                });
    }

    @Test
    void i4_x06() throws Exception {
        both(
                "writers-reject-escape-and-poison",
                f -> {
                    f.pending(f.q(0));
                    var lease = f.lease(0);
                    assertThrows(
                            RuntimeException.class,
                            () -> f.admissions.insertInitial(f.definition, 0, f.q(0)));
                    assertThrows(
                            RuntimeException.class, () -> f.admissions.currentCheckpoint(lease));
                    assertThrows(RuntimeException.class, () -> f.admissions.liveSlots(lease));
                    assertThrows(
                            RuntimeException.class,
                            () -> f.admissions.contains(f.definition, 0, f.q(0)));
                    assertThrows(
                            RuntimeException.class,
                            () -> f.admissions.advanceCheckpoint(lease, 0, 1));
                    assertThrows(
                            AttemptTransactions.Failure.class,
                            () ->
                                    f.target.tx.write(
                                            () -> {
                                                f.admissions.insertInitial(f.definition, 0, f.q(0));
                                                try {
                                                    f.admissions.insertInitial(
                                                            f.definition, 1, f.q(1));
                                                } catch (IllegalArgumentException expected) {
                                                }
                                                return null;
                                            }));
                    assertTrue(f.slotIds().isEmpty());
                    var inner =
                            new org.springframework.transaction.support.TransactionTemplate(
                                    ConsumptionLifecyclePostgresFixture.jdbcManager(
                                            f.target.source));
                    inner.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_REQUIRES_NEW);
                    assertThrows(
                            AttemptTransactions.Failure.class,
                            () ->
                                    f.target.tx.write(
                                            () -> {
                                                try {
                                                    inner.execute(
                                                            status -> {
                                                                f.admissions.insertInitial(
                                                                        f.definition, 0, f.q(0));
                                                                return null;
                                                            });
                                                } catch (RuntimeException expected) {
                                                }
                                                return null;
                                            }));
                    assertTrue(f.slotIds().isEmpty());
                    assertThrows(
                            RuntimeException.class,
                            () -> f.target.tx.write(() -> f.target.tx.write(() -> null)));
                    assertTrue(f.slotIds().isEmpty());
                });
    }

    @Test
    void i4_x08() throws Exception {
        both(
                "catalogue-and-rights",
                f -> {
                    var keys =
                            f.target.oracle.queryForList(
                                    "select pg_get_constraintdef(oid) def from pg_constraint where"
                                        + " conrelid='consumption_slot'::regclass and contype='f'");
                    assertEquals(1, keys.size());
                    assertTrue(
                            keys.getFirst()
                                    .get("def")
                                    .toString()
                                    .contains("consumption_routing_contract"));
                    assertFalse(
                            f.target.sql.queryForObject(
                                    "select"
                                        + " has_table_privilege('consumption_routing_contract','INSERT,UPDATE,DELETE,TRUNCATE')",
                                    Boolean.class));
                    assertFalse(
                            f.target.sql.queryForObject(
                                    "select"
                                        + " has_table_privilege('discovery_checkpoint','DELETE,TRUNCATE')",
                                    Boolean.class));
                    assertTrue(
                            f.target.oracle.queryForObject(
                                    "select bool_and(not prosecdef) from pg_proc where"
                                            + " pronamespace=(select oid from pg_namespace where"
                                            + " nspname=current_schema())",
                                    Boolean.class));
                    f.trace.add(
                            "constraints="
                                    + f.target.oracle.queryForList(
                                            "select conname,pg_get_constraintdef(oid) from"
                                                + " pg_constraint where connamespace=(select oid"
                                                + " from pg_namespace where"
                                                + " nspname=current_schema()) order by conname"));
                    f.trace.add(
                            "indexes="
                                    + f.target.oracle.queryForList(
                                            "select indexname,indexdef from pg_indexes where"
                                                    + " schemaname=current_schema() order by"
                                                    + " indexname"));
                    f.trace.add(
                            "triggers="
                                    + f.target.oracle.queryForList(
                                            "select tgname,pg_get_triggerdef(oid) from pg_trigger"
                                                + " where tgrelid in"
                                                + " ('consumption_slot'::regclass,'discovery_checkpoint'::regclass)"
                                                + " order by tgname"));
                });
    }

    @Test
    void i4_x09() throws Exception {
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "three-independent-spaces",
                f -> {
                    four(f);
                    var cs =
                            f.target.oracle.queryForList(
                                    "select consumption_id from consumption_routing_contract where"
                                            + " family='POT_EVENT' order by consumption_id",
                                    UUID.class);
                    assertEquals(3, cs.size());
                    for (int n = 0; n < 3; n++) {
                        var definition =
                                new ConsumptionDefinition(
                                        new ConsumptionId(cs.get(n)),
                                        f.family,
                                        ConsumptionDefinition.Authority.READ);
                        var lease =
                                f.target.technical(
                                        () ->
                                                f.target
                                                        .ownership
                                                        .acquire(
                                                                definition,
                                                                0,
                                                                UUID.randomUUID(),
                                                                Duration.ofSeconds(30))
                                                        .orElseThrow());
                        var store =
                                n == 1
                                        ? DiscoveryPostgresFixture.wrap(
                                                DiscoveryAdmissionStore.class,
                                                f.admissions,
                                                (m, a) -> {},
                                                (m, a) -> {
                                                    if (m.equals("insertInitial"))
                                                        throw new IllegalStateException(
                                                                "one Consumption fails");
                                                })
                                        : f.admissions;
                        var d =
                                new DiscoveryEngine(
                                        definition,
                                        f.target.tx,
                                        f.target.ownership,
                                        store,
                                        f.settings,
                                        f.write.tx,
                                        f.events,
                                        r -> true);
                        if (n == 1)
                            assertThrows(
                                    AttemptTransactions.Failure.class,
                                    () -> d.discoverEvents(lease));
                        else assertEquals(2, d.discoverEvents(lease).inserted());
                        int count =
                                f.target.oracle.queryForObject(
                                        "select count(*) from consumption_slot where"
                                                + " consumption_id=?",
                                        Integer.class,
                                        cs.get(n));
                        assertEquals(n == 1 ? 0 : 2, count);
                    }
                    assertEquals(
                            4,
                            f.target.oracle.queryForObject(
                                    "select count(*) from consumption_slot", Integer.class));
                    assertEquals(
                            2,
                            f.target.oracle.queryForObject(
                                    "select count(*) from discovery_checkpoint where position=4",
                                    Integer.class));
                    independentEffects(f, cs);
                });
    }

    @Test
    void i4_x10() throws Exception {
        commandFinalizationLostAck();
        scenario(
                ConsumableFamily.COMMAND,
                true,
                "Changed-Unchanged-Rejected",
                f -> {
                    var lease = f.lease(0);
                    f.pending(f.q(0));
                    f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    f.complete(lease, f.q(0));
                    var pot =
                            f.write.oracle.queryForObject(
                                    "select pot_id from v3_command where request_id=?",
                                    UUID.class,
                                    f.q(0));
                    for (int n = 1; n <= 2; n++)
                        f.write.oracle.update(
                                "insert into"
                                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                    + " values(?,'i3','actor','fixture',?,?,'UpdatePotDetails',?::jsonb)",
                                f.q(n),
                                pot,
                                n == 1 ? 1 : 999,
                                PotCommandJson.encode(
                                        new PotCommand.UpdatePotDetails(new Label("I4 expected"))));
                    assertEquals(
                            2,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    for (int n = 1; n <= 2; n++) f.complete(lease, f.q(n));
                    assertEquals(
                            List.of("CHANGED", "UNCHANGED", "REJECTED"),
                            f.write.oracle.queryForList(
                                    "select outcome from v3_command_result order by request_id",
                                    String.class));
                    assertEquals(
                            1,
                            f.write.oracle.queryForObject(
                                    "select count(*) from v3_pot_event", Integer.class));
                    assertEquals(
                            3,
                            f.write.oracle.queryForObject(
                                    "select count(*) from v3_command_completion_event",
                                    Integer.class));
                    assertEquals(
                            0,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertTrue(f.slotIds().isEmpty());
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    f.write.oracle.update(
                                            "update v3_command set observed_version=1 where"
                                                    + " request_id=?",
                                            f.q(0)));
                });
    }

    @Test
    void i4_x07() throws Exception {
        twoCommandFinalizers();
        both(
                "A-naive-physical-cycle-counterexample",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var before = f.slotIds();
                    try (var d = f.write.admin.getConnection();
                            var fin = f.write.admin.getConnection()) {
                        d.setAutoCommit(false);
                        fin.setAutoCommit(false);
                        int dp, fp;
                        try (var st = d.createStatement();
                                var rs = st.executeQuery("select pg_backend_pid()")) {
                            rs.next();
                            dp = rs.getInt(1);
                        }
                        try (var st = fin.createStatement();
                                var rs = st.executeQuery("select pg_backend_pid()")) {
                            rs.next();
                            fp = rs.getInt(1);
                        }
                        for (var c : List.of(d, fin))
                            try (var st = c.createStatement()) {
                                st.execute(
                                        "set local deadlock_timeout='200ms';set local"
                                                + " lock_timeout='5s'");
                            }
                        try (var st =
                                d.prepareStatement(
                                        "select "
                                                + f.requestKey()
                                                + " from "
                                                + f.requestTable()
                                                + " where "
                                                + f.requestKey()
                                                + "=? for update")) {
                            st.setObject(1, f.q(0));
                            st.executeQuery().close();
                        }
                        try (var st =
                                fin.prepareStatement(
                                        "select consumable_id from consumption_slot where"
                                            + " consumption_id=? and consumable_id=? for update")) {
                            st.setObject(1, f.definition.id().value());
                            st.setObject(2, f.q(0));
                            st.executeQuery().close();
                        }
                        var fwait =
                                f.target.threads.submit(
                                        () -> {
                                            try (var st =
                                                    fin.prepareStatement(
                                                            "select "
                                                                    + f.requestKey()
                                                                    + " from "
                                                                    + f.requestTable()
                                                                    + " where "
                                                                    + f.requestKey()
                                                                    + "=? for update")) {
                                                st.setObject(1, f.q(0));
                                                st.executeQuery().close();
                                                return "OK";
                                            } catch (SQLException x) {
                                                f.trace.add("A naive F " + x);
                                                return x.getSQLState();
                                            }
                                        });
                        f.write.blocked(fp);
                        f.locks("A naive F waits D source");
                        var dwait =
                                f.target.threads.submit(
                                        () -> {
                                            try (var st =
                                                    d.prepareStatement(
                                                            "select consumable_id from"
                                                                + " consumption_slot where"
                                                                + " consumption_id=? and"
                                                                + " consumable_id=? for update")) {
                                                st.setObject(1, f.definition.id().value());
                                                st.setObject(2, f.q(0));
                                                st.executeQuery().close();
                                                return "OK";
                                            } catch (SQLException x) {
                                                f.trace.add("A naive D " + x);
                                                return x.getSQLState();
                                            }
                                        });
                        String dr = dwait.get(8, TimeUnit.SECONDS);
                        d.rollback();
                        fin.rollback();
                        String fr = fwait.get(8, TimeUnit.SECONDS);
                        assertTrue(dr.equals("40P01") || fr.equals("40P01"));
                        f.trace.add(
                                "A NAIVE ONLY expected deadlock: D="
                                        + dp
                                        + " F="
                                        + fp
                                        + " resolutions rollback/rollback states="
                                        + dr
                                        + ","
                                        + fr);
                    }
                    assertEquals(before, f.slotIds());
                });
        scenario(
                ConsumableFamily.COMMAND,
                true,
                "A-corrected-wait-expiry-rejected",
                f -> {
                    f.pending(f.q(0));
                    f.slot(f.q(0), false);
                    var lease = f.lease(0);
                    try (var fin = new PausedFinalizer(f, lease, f.q(0), "after-FK", false)) {
                        var deadline =
                                f.target
                                        .oracle
                                        .queryForObject(
                                                "update partition_ownership set"
                                                    + " owned_until=clock_timestamp()+interval '1"
                                                    + " second' where consumption_id=? and"
                                                    + " partition_id=0 returning owned_until",
                                                Timestamp.class,
                                                f.definition.id().value())
                                        .toInstant();
                        var pid = new AtomicInteger();
                        var entered = new CountDownLatch(1);
                        var d =
                                f.target.threads.submit(
                                        () ->
                                                assertThrows(
                                                        AttemptTransactions.Failure.class,
                                                        () ->
                                                                f.target.tx.write(
                                                                        () -> {
                                                                            f.target.ownership
                                                                                    .bound(
                                                                                            Duration
                                                                                                    .ofSeconds(
                                                                                                            5),
                                                                                            Duration
                                                                                                    .ofSeconds(
                                                                                                            10));
                                                                            assertTrue(
                                                                                    f.target
                                                                                            .ownership
                                                                                            .protectAcquisition(
                                                                                                    f.definition,
                                                                                                    lease)
                                                                                            .isPresent());
                                                                            pid.set(
                                                                                    f.target.sql
                                                                                            .queryForObject(
                                                                                                    "select"
                                                                                                        + " pg_backend_pid()",
                                                                                                    Integer
                                                                                                            .class));
                                                                            entered.countDown();
                                                                            f.target.sql
                                                                                    .queryForList(
                                                                                            "select"
                                                                                                + " request_id"
                                                                                                + " from"
                                                                                                + " v3_command"
                                                                                                + " where"
                                                                                                + " request_id=?"
                                                                                                + " and status='PENDING'"
                                                                                                + " for update",
                                                                                            f.q(0));
                                                                            if (f.target
                                                                                    .ownership
                                                                                    .protectAcquisition(
                                                                                            f.definition,
                                                                                            lease)
                                                                                    .isEmpty())
                                                                                throw new IllegalStateException(
                                                                                        "A corrected"
                                                                                            + " expired"
                                                                                            + " after"
                                                                                            + " source"
                                                                                            + " wait");
                                                                            return null;
                                                                        })));
                        assertTrue(entered.await(8, TimeUnit.SECONDS));
                        f.target.blocked(pid.get());
                        f.target.await(() -> f.target.now().isAfter(deadline));
                        f.locks("A corrected source wait then expiry");
                        assertEquals(ConsumptionEngine.Status.COMMITTED, fin.finish().status());
                        assertEquals(
                                AttemptTransactions.Resolution.ROLLED_BACK,
                                d.get(8, TimeUnit.SECONDS).resolution());
                        assertTrue(f.slotIds().isEmpty());
                    }
                });
        // The actual B production operation is re-executed against each closed F interleaving.
        i4_b07();
        i4_b08();
    }

    void commandFinalizationLostAck() throws Exception {
        scenario(
                ConsumableFamily.COMMAND,
                true,
                "Command-finalization-AFTER_COMMIT-ACK-lost",
                f -> {
                    f.pending(f.q(0));
                    var lease = f.lease(0);
                    assertEquals(
                            1,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    try (var proxy =
                            new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
                        var ds =
                                new DriverManagerDataSource(
                                        "jdbc:postgresql://"
                                                + PG.getHost()
                                                + ":"
                                                + proxy.localPort()
                                                + "/"
                                                + f.write.database
                                                + "?currentSchema="
                                                + f.write.schema
                                                + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                        f.write.runtimeRole,
                                        "i3-fixture");
                        var emf = new LocalContainerEntityManagerFactoryBean();
                        emf.setDataSource(ds);
                        emf.setPackagesToScan(
                                "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
                        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
                        emf.afterPropertiesSet();
                        try {
                            var manager = new JpaTransactionManager(emf.getObject());
                            manager.setDataSource(ds);
                            manager.setNestedTransactionAllowed(false);
                            var owner = new SpringAttemptTransactions(ds, manager);
                            var armed = new AtomicBoolean();
                            AttemptTransactions tx =
                                    new AttemptTransactions() {
                                        public <T> T read(
                                                java.util.function.Function<RecoveryReadContext, T>
                                                        a) {
                                            return owner.read(a);
                                        }

                                        public <T> T write(java.util.function.Supplier<T> a) {
                                            if (armed.compareAndSet(true, false))
                                                proxy.armed.set(
                                                        CommitAcknowledgementProxy.Fault
                                                                .AFTER_COMMIT);
                                            return owner.write(a);
                                        }

                                        public void poison(Throwable t) {
                                            owner.poison(t);
                                        }

                                        public void requirePublicationClosed() {
                                            owner.requirePublicationClosed();
                                        }
                                    };
                            var e =
                                    new ConsumptionEngine(
                                            f.definition,
                                            tx,
                                            new JdbcConsumptionSlotStore(ds),
                                            f.write.participantFor(ds, owner),
                                            new JdbcPartitionOwnershipStore(ds),
                                            f.target.settings,
                                            f.capacity,
                                            c ->
                                                    AttemptFailureClassifier.FailureKind
                                                            .NON_COUNTABLE);
                            var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
                            armed.set(true);
                            var result = e.execute(claim);
                            assertEquals(
                                    AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                            assertEquals(1, proxy.committedReplies.get());
                            assertEquals(
                                    "TERMINAL",
                                    f.write.oracle.queryForObject(
                                            "select status from v3_command where request_id=?",
                                            String.class,
                                            f.q(0)));
                            for (var table :
                                    List.of(
                                            "v3_command_result",
                                            "v3_command_completion_event",
                                            "v3_pot_event"))
                                assertEquals(
                                        1,
                                        f.write.oracle.queryForObject(
                                                "select count(*) from " + table, Integer.class));
                            assertTrue(f.slotIds().isEmpty());
                            assertEquals(
                                    0,
                                    f.engine()
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh())
                                            .committedAdmissions());
                            e.settlePending();
                            assertTrue(f.slotIds().isEmpty());
                            f.trace.add(
                                    "Command actual JPA owner D->I3->I2 AFTER_COMMIT real server"
                                        + " ACK suppressed; UNKNOWN historical outcome; exterior"
                                        + " terminal/result/completion/Pot each exact=1; no"
                                        + " reenrollment");
                        } finally {
                            emf.destroy();
                        }
                    }
                });
    }

    void twoCommandFinalizers() throws Exception {
        for (boolean samePot : List.of(false, true))
            for (boolean sameIdentity : List.of(false, true))
                scenario(
                        ConsumableFamily.COMMAND,
                        true,
                        "two-Command-Pot-" + samePot + "-EUI-" + sameIdentity + "-counter-lock",
                        f -> {
                            produceReal(f, 2);
                            f.target.oracle.update(
                                    "update partition_ownership set"
                                        + " owned_until=clock_timestamp()-interval '1 second' where"
                                        + " consumption_id=? and partition_id=0 and owner_token is"
                                        + " not null",
                                    f.definition.id().value());
                            var pots =
                                    f.write.oracle.queryForList(
                                            "select pot_id from v3_command order by request_id",
                                            UUID.class);
                            f.write.oracle.update(
                                    "insert into fixture_identity select"
                                            + " issuer,'actor2',user_id,token from fixture_identity"
                                            + " where subject='actor'");
                            for (int n = 0; n < 2; n++)
                                f.write.oracle.update(
                                        "insert into"
                                            + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                            + " values(?,'i3',?,'fixture',?,1,'UpdatePotDetails',?::jsonb)",
                                        f.q(n),
                                        n == 0 || sameIdentity ? "actor" : "actor2",
                                        pots.get(n == 0 || samePot ? 0 : 1),
                                        PotCommandJson.encode(
                                                new PotCommand.UpdatePotDetails(
                                                        new Label("I4 matrix " + n))));
                            var lease = f.lease(0);
                            assertEquals(
                                    2,
                                    f.engine()
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh())
                                            .committedAdmissions());
                            try (var counter = f.write.admin.getConnection();
                                    var first =
                                            new PausedFinalizer(
                                                    f, lease, f.q(0), "after-FK", false)) {
                                counter.setAutoCommit(false);
                                int counterPid;
                                try (var st = counter.createStatement();
                                        var rs =
                                                st.executeQuery(
                                                        "select pg_backend_pid(),position from"
                                                            + " v3_event_counter where family='POT'"
                                                            + " for update")) {
                                    rs.next();
                                    counterPid = rs.getInt(1);
                                }
                                var pid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                var delegate = f.participant;
                                var participant =
                                        new ConsumptionParticipant() {
                                            public Optional<Prepared> prepare(UUID id) {
                                                return delegate.prepare(id)
                                                        .map(
                                                                p ->
                                                                        new Prepared() {
                                                                            public void finalizeIn(
                                                                                    SlotCompletion
                                                                                            c) {
                                                                                pid.set(
                                                                                        f.write.sql
                                                                                                .queryForObject(
                                                                                                        "select"
                                                                                                            + " pg_backend_pid()",
                                                                                                        Integer
                                                                                                                .class));
                                                                                entered.countDown();
                                                                                p.finalizeIn(c);
                                                                            }

                                                                            public void
                                                                                    requireCompletionReady() {
                                                                                p
                                                                                        .requireCompletionReady();
                                                                            }
                                                                        });
                                            }

                                            public RecoveryObservation observe(
                                                    UUID id, RecoveryReadContext c) {
                                                return delegate.observe(id, c);
                                            }
                                        };
                                var finalizer =
                                        new ConsumptionEngine(
                                                f.definition,
                                                f.target.tx,
                                                f.target.slots,
                                                participant,
                                                f.target.ownership,
                                                f.target.settings,
                                                f.capacity,
                                                c ->
                                                        AttemptFailureClassifier.FailureKind
                                                                .NON_COUNTABLE);
                                var second =
                                        finalizer
                                                .acquire(lease, List.of(f.q(1)))
                                                .claims()
                                                .getFirst();
                                var other =
                                        f.target.threads.submit(() -> finalizer.execute(second));
                                assertTrue(entered.await(8, TimeUnit.SECONDS));
                                f.target.blocked(pid.get());
                                f.locks(
                                        "two actual F, EUI/Pot/counter waits; counterPID="
                                                + counterPid);
                                assertFalse(first.future.isDone());
                                assertFalse(other.isDone());
                                var d =
                                        f.engine()
                                                .discoverRequests(
                                                        lease, DiscoveryEngine.RequestWalk.fresh());
                                assertEquals(0, d.committedAdmissions());
                                assertFalse(first.future.isDone());
                                assertFalse(other.isDone());
                                first.release();
                                f.target.blocked(first.pid);
                                f.locks("F1 now counter wait; D already physically committed");
                                counter.rollback();
                                assertEquals(
                                        ConsumptionEngine.Status.COMMITTED,
                                        first.finish().status());
                                var result = other.get(10, TimeUnit.SECONDS);
                                f.trace.add(
                                        "second actual F initial resolution="
                                                + result
                                                + " samePot="
                                                + samePot
                                                + " sameEUI="
                                                + sameIdentity);
                                if (result.status() != ConsumptionEngine.Status.COMMITTED) {
                                    f.target.oracle.update(
                                            "update consumption_slot set"
                                                    + " claimed_until=clock_timestamp()-interval '1"
                                                    + " second' where consumption_id=? and"
                                                    + " consumable_id=?",
                                            f.definition.id().value(),
                                            f.q(1));
                                    f.complete(lease, f.q(1));
                                }
                            }
                            assertEquals(
                                    4,
                                    f.write.oracle.queryForObject(
                                            "select count(*) from v3_command where"
                                                    + " status='TERMINAL'",
                                            Integer.class));
                            assertEquals(
                                    4,
                                    f.write.oracle.queryForObject(
                                            "select count(*) from v3_command_result",
                                            Integer.class));
                            assertEquals(
                                    4,
                                    f.write.oracle.queryForObject(
                                            "select count(*) from v3_command_completion_event",
                                            Integer.class));
                            assertTrue(f.slotIds().isEmpty());
                            assertEquals(
                                    0,
                                    f.engine()
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh())
                                            .committedAdmissions());
                        });
    }

    static final AtomicLong PERFORMANCE_C = new AtomicLong(50000);

    static List<UUID> input(ConsumableFamily family, int partition) throws Exception {
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(
                                ConsumptionLifecyclePostgresFixture.ROOT
                                        .resolve(
                                                "docs/testing/evidence/L2-I4/Performance-inputs.json")
                                        .toFile());
        var result = new ArrayList<UUID>();
        for (var value : json.path("families").path(family.name()).path("p" + partition))
            result.add(UUID.fromString(value.asText()));
        return result;
    }

    void costMetadata(DiscoveryPostgresFixture f) {
        f.trace.add(
                "COST environment image="
                        + ConsumptionLifecyclePostgresFixture.IMAGE
                        + " seed=1000000; statistics cumulative/asynchronous, not synchronous"
                        + " per-call counters");
        for (var authority : f.target == f.write ? List.of(f.write) : List.of(f.write, f.target)) {
            var sql = authority.oracle;
            f.trace.add(
                    "COST authority/version="
                            + sql.queryForMap(
                                    "select"
                                        + " current_database(),current_schema(),version(),clock_timestamp()"));
            f.trace.add(
                    "COST effective runtime identity="
                            + authority.sql.queryForMap(
                                    "select"
                                        + " session_user,current_user,current_database(),current_schema()"));
            f.trace.add(
                    "COST PostgreSQL settings="
                            + sql.queryForList(
                                    "select name,setting,unit from pg_settings where name in"
                                        + " ('max_connections','shared_buffers','work_mem','fsync','synchronous_commit','track_io_timing','server_version','default_transaction_isolation')"
                                        + " order by name"));
            f.trace.add(
                    "COST catalog columns="
                            + sql.queryForList(
                                    "select"
                                        + " table_name,column_name,data_type,is_nullable,column_default"
                                        + " from information_schema.columns where"
                                        + " table_schema=current_schema() order by"
                                        + " table_name,ordinal_position"));
            f.trace.add(
                    "COST catalog constraints="
                            + sql.queryForList(
                                    "select"
                                        + " conname,contype,conrelid::regclass::text,pg_get_constraintdef(oid)"
                                        + " from pg_constraint where connamespace=(select oid from"
                                        + " pg_namespace where nspname=current_schema()) order by"
                                        + " conname"));
            f.trace.add(
                    "COST catalog indexes="
                            + sql.queryForList(
                                    "select tablename,indexname,indexdef from pg_indexes where"
                                            + " schemaname=current_schema() order by"
                                            + " tablename,indexname"));
            f.trace.add(
                    "COST planner relation estimates="
                            + sql.queryForList(
                                    "select relname,relkind,reltuples,relpages from pg_class where"
                                            + " relnamespace=(select oid from pg_namespace where"
                                            + " nspname=current_schema()) and relkind in ('r','i')"
                                            + " order by relname"));
            f.trace.add(
                    "COST table statistics="
                            + sql.queryForList(
                                    "select"
                                        + " relname,n_live_tup,n_dead_tup,n_mod_since_analyze,analyze_count,autoanalyze_count,last_analyze,last_autoanalyze"
                                        + " from pg_stat_user_tables where"
                                        + " schemaname=current_schema() order by relname"));
            f.trace.add(
                    "COST IO statistics="
                            + sql.queryForList(
                                    "select"
                                        + " relname,heap_blks_read,heap_blks_hit,idx_blks_read,idx_blks_hit"
                                        + " from pg_statio_user_tables where"
                                        + " schemaname=current_schema() order by relname"));
        }
    }

    void eventCosts(DiscoveryPostgresFixture f, List<UUID> ids, Set<UUID> localIds)
            throws Exception {
        f.snapshot("P01 complete dataset before measured samples");
        costMetadata(f);
        // Every sample gets an explicitly administered independent technical Consumption space.
        // No reset/delete or false idle admission sample; five warmups precede ten measured
        // samples.
        // Sequential cost fixture: three explicit independent physical connections. This
        // removes repeated TCP/type-cache setup from SQL costs, not from the I5 resource proof.
        var effectsConnection =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        f.target.source.getConnection(), true);
        var sourceConnection =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        f.write.source.getConnection(), true);
        var adminConnection =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        f.target.admin.getConnection(), true);
        try {
            var admin = new JdbcTemplate(adminConnection);
            f.trace.add(
                    "P01 measurement topology=single-thread, pinned WRITE-source/READ-effects/admin"
                            + " connections; excludes pool/connection acquisition. I5 resources NOT"
                            + " certified");
            var meter = new WriteRevisionPerformancePostgresTest.Meter(effectsConnection);
            var owner =
                    new SpringAttemptTransactions(
                            meter, ConsumptionLifecyclePostgresFixture.jdbcManager(meter));
            var ownership = new JdbcPartitionOwnershipStore(meter);
            var sourceMeter = new WriteRevisionPerformancePostgresTest.Meter(sourceConnection);
            var sourceReads =
                    new SpringAttemptTransactions(
                            sourceMeter,
                            ConsumptionLifecyclePostgresFixture.jdbcManager(sourceMeter));
            NonBurnableReferences source;
            if (f.events instanceof JdbcEventDiscoveryReferences)
                source = new JdbcEventDiscoveryReferences(sourceMeter, f.family, f.settings);
            else
                source =
                        new NonBurnableReferences() {
                            final JdbcTemplate sql = new JdbcTemplate(sourceMeter);

                            public ConsumableFamily family() {
                                return f.family;
                            }

                            public Window window(long k, int max, RecoveryReadContext c) {
                                SpringAttemptTransactions.requireRead(sourceMeter, c);
                                long safe =
                                        sql.queryForObject(
                                                "select position from fixture_discovery_counter",
                                                Long.class);
                                long high = k + Math.min((long) max, safe - k);
                                return new Window(
                                        k,
                                        high,
                                        sql.query(
                                                "select position,id,subject from"
                                                    + " fixture_discovery_event where position>?"
                                                    + " and position<=? order by position",
                                                (r, n) ->
                                                        new Reference(
                                                                r.getLong(1),
                                                                r.getObject(2, UUID.class),
                                                                r.getString(3)),
                                                k,
                                                high));
                            }
                        };
            meter.enabled = true;
            sourceMeter.enabled = true;
            for (int w : List.of(1, 32, 128))
                for (int b : List.of(1, 16, 32))
                    for (long cap : List.of(1L, 64L)) {
                        var settings =
                                new DiscoveryEngine.Settings(
                                        w,
                                        32,
                                        b,
                                        cap,
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(10));
                        var elapsed = new ArrayList<Long>();
                        for (int sample = -5; sample < 10; sample++) {
                            UUID cid =
                                    new UUID(0x4934504552460000L, PERFORMANCE_C.incrementAndGet());
                            adminConnection.getConnection().setAutoCommit(false);
                            admin.update(
                                    "insert into consumption_routing_contract"
                                            + " values(?,?,'sha256-v1',4)",
                                    cid,
                                    f.family.name());
                            admin.update(
                                    "insert into partition_ownership(consumption_id,partition_id)"
                                            + " select ?,p from generate_series(0,3)p",
                                    cid);
                            adminConnection.getConnection().commit();
                            adminConnection.getConnection().setAutoCommit(true);
                            var definition =
                                    new ConsumptionDefinition(
                                            new ConsumptionId(cid),
                                            f.family,
                                            ConsumptionDefinition.Authority.READ);
                            var lease =
                                    owner.write(
                                            () -> {
                                                ownership.bound(
                                                        settings.lockTimeout(),
                                                        settings.statementTimeout());
                                                return ownership
                                                        .acquire(
                                                                definition,
                                                                0,
                                                                UUID.randomUUID(),
                                                                Duration.ofSeconds(30))
                                                        .orElseThrow();
                                            });
                            var d =
                                    new DiscoveryEngine(
                                            definition,
                                            owner,
                                            ownership,
                                            new JdbcDiscoveryAdmissionStore(meter, settings),
                                            settings,
                                            sourceReads,
                                            source,
                                            r -> true);
                            var expected = new HashSet<UUID>();
                            long covered = 0;
                            int budget = (int) Math.min(cap, b);
                            for (int p = 0; p < Math.min(w, ids.size()); p++) {
                                UUID id = ids.get(p);
                                if (localIds.contains(id)) {
                                    if (expected.size() == budget) break;
                                    expected.add(id);
                                }
                                covered = p + 1;
                            }
                            meter.reset();
                            sourceMeter.reset();
                            String walBefore =
                                    admin.queryForObject(
                                            "select pg_current_wal_insert_lsn()::text",
                                            String.class);
                            long allocatedBefore = allocatedBytes();
                            long start = System.nanoTime();
                            var r = d.discoverEvents(lease);
                            long ns = System.nanoTime() - start;
                            long allocation = allocatedBytes() - allocatedBefore;
                            long walBytes =
                                    admin.queryForObject(
                                                    "select"
                                                        + " pg_wal_lsn_diff(pg_current_wal_insert_lsn(),?::pg_lsn)",
                                                    java.math.BigDecimal.class,
                                                    walBefore)
                                            .longValueExact();
                            assertEquals(covered, r.checkpoint().orElseThrow());
                            assertEquals(expected.size(), r.inserted());
                            assertEquals(
                                    expected,
                                    new HashSet<>(
                                            admin.queryForList(
                                                    "select consumable_id from consumption_slot"
                                                            + " where consumption_id=?",
                                                    UUID.class,
                                                    cid)));
                            assertTrue(r.inserted() <= b && r.inserted() <= cap);
                            assertTrue(r.examined() <= w);
                            assertTrue(sourceMeter.statements > 0);
                            assertEquals(
                                    Math.min(w, ids.size())
                                            + (f.events instanceof JdbcEventDiscoveryReferences
                                                    ? 3
                                                    : 1),
                                    sourceMeter.rows);
                            if (sample >= 0) {
                                elapsed.add(ns);
                                f.trace.add(
                                        "P01 sample="
                                                + sample
                                                + " population="
                                                + ids.size()
                                                + " W="
                                                + w
                                                + " B="
                                                + b
                                                + " cap="
                                                + cap
                                                + " ns="
                                                + ns
                                                + " cumulativeClientAllocatedBytes="
                                                + allocation
                                                + " clusterWalUpperBoundBytes="
                                                + walBytes
                                                + " JDBC effectsStatements="
                                                + meter.statements
                                                + " sourceStatements="
                                                + sourceMeter.statements
                                                + " sourceConsumedRows="
                                                + sourceMeter.rows
                                                + " consumed rows="
                                                + meter.rows
                                                + " globalExamined="
                                                + r.examined()
                                                + " inserted="
                                                + r.inserted()
                                                + " K="
                                                + covered
                                                + " C="
                                                + cid);
                            }
                        }
                        Collections.sort(elapsed);
                        assertEquals(10, elapsed.size());
                        f.trace.add(
                                "P01 observed medianNs="
                                        + elapsed.get(5)
                                        + " maxNs="
                                        + elapsed.get(9)
                                        + " settings="
                                        + settings);
                    }
        } finally {
            effectsConnection.destroy();
            sourceConnection.destroy();
            adminConnection.destroy();
        }
    }

    @Test
    void i4_p01() throws Exception {
        for (int size : List.of(100, 1000, 10000))
            for (int ratio : List.of(0, 25, 100))
                five(
                        "cost-N" + size + "-local" + ratio,
                        f -> {
                            var local = input(f.family, 0);
                            var other = input(f.family, 1);
                            var ids = new ArrayList<UUID>();
                            var locals = new HashSet<UUID>();
                            int a = 0, b = 0;
                            for (int n = 0; n < size; n++) {
                                boolean here = ratio == 100 || ratio == 25 && n % 4 == 0;
                                UUID id = here ? local.get(a++) : other.get(b++);
                                ids.add(id);
                                if (here) locals.add(id);
                            }
                            f.fixtureEvents(ids);
                            f.trace.add(
                                    "P01 independent Python input population="
                                            + size
                                            + " local="
                                            + locals.size()
                                            + " ratio="
                                            + ratio);
                            eventCosts(f, ids, locals);
                            f.trace.add(
                                    "P01 COUNT plan="
                                            + f.target.oracle.queryForList(
                                                    "explain (analyze,buffers,wal,format json)"
                                                        + " select count(*) from consumption_slot"
                                                        + " where consumption_id='"
                                                            + f.definition.id().value()
                                                            + "' and partition_id=0 and"
                                                            + " state<>'QUARANTINED'"));
                        });
        // The delivered real producer is shared by the two actual publication families at each
        // volume.
        for (int size : List.of(100, 1000, 10000))
            real(
                    "real-cost-N" + size,
                    f -> {
                        produceReal(f, size);
                        String sequence =
                                f.family == ConsumableFamily.POT_EVENT
                                        ? "POT"
                                        : "COMMAND_COMPLETION";
                        var ids =
                                f.write.oracle.queryForList(
                                        "select event_id from v3_event_position where family=?"
                                                + " order by position",
                                        UUID.class,
                                        sequence);
                        var routing = externalPartitions(f.family, ids);
                        var locals = new HashSet<UUID>();
                        routing.forEach(
                                (id, partition) -> {
                                    if (partition == 0) locals.add(id);
                                });
                        f.trace.add("P01 external Python routing=" + routing);
                        eventCosts(f, ids, locals);
                    });
    }

    void bulkPending(DiscoveryPostgresFixture f, List<UUID> ids) throws Exception {
        try (var c = f.write.admin.getConnection()) {
            c.setAutoCommit(false);
            String sql =
                    f.family == ConsumableFamily.COMMAND
                            ? "insert into"
                                  + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                  + " values(?,'i3','actor','fixture',gen_random_uuid(),0,'CreatePot',?::jsonb)"
                            : "insert into fixture_discovery_request(id) values(?)";
            try (var st = c.prepareStatement(sql)) {
                for (UUID id : ids) {
                    st.setObject(1, id);
                    if (f.family == ConsumableFamily.COMMAND)
                        st.setString(
                                2,
                                PotCommandJson.encode(
                                        new PotCommand.CreatePot(
                                                new Label("performance fixture"))));
                    st.addBatch();
                }
                st.executeBatch();
            }
            c.commit();
        }
    }

    void bulkSlots(DiscoveryPostgresFixture f, List<UUID> ids, int quarantinePercent)
            throws Exception {
        try (var c = f.target.admin.getConnection()) {
            c.setAutoCommit(false);
            try (var st =
                    c.prepareStatement(
                            "insert into"
                                + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state)"
                                + " select ?,?,consumption_hash_v1(?,?),0,?")) {
                int n = 0;
                for (UUID id : ids) {
                    st.setObject(1, f.definition.id().value());
                    st.setObject(2, id);
                    st.setString(3, f.family.name());
                    st.setObject(4, id);
                    st.setString(5, n++ % 100 < quarantinePercent ? "QUARANTINED" : "AVAILABLE");
                    st.addBatch();
                }
                st.executeBatch();
            }
            c.commit();
        }
    }

    void duplicateLockedCost() throws Exception {
        both(
                "majority-duplicate-locked-before-new",
                f -> {
                    for (int n = 0; n < 5; n++) f.pending(f.q(n));
                    f.slot(f.q(0), false);
                    f.slot(f.q(1), false);
                    f.slot(f.q(3), true);
                    var lease = f.lease(0);
                    try (var cost = new CostDiscovery(f);
                            var locked = f.lockSource(f.q(2))) {
                        var r = cost.discover(lease, DiscoveryEngine.RequestWalk.fresh());
                        assertEquals(5, r.examined());
                        assertEquals(5, r.advanced());
                        assertEquals(1, r.committedAdmissions());
                        assertTrue(r.tourFinished());
                        assertFalse(f.slotIds().contains(f.q(2)));
                        f.locks(
                                "majority duplicate/locked page physically committed while source"
                                        + " held");
                        locked.rollback();
                    }
                    assertEquals(
                            1,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .committedAdmissions());
                    assertEquals(5, f.slotIds().size());
                });
    }

    final class CostDiscovery implements AutoCloseable {
        final org.springframework.jdbc.datasource.SingleConnectionDataSource connection;
        final WriteRevisionPerformancePostgresTest.Meter meter;
        final DiscoveryEngine engine;
        final DiscoveryPostgresFixture f;
        final ExecutorService dispatcher =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("i4-cost-harness-dispatch").factory());

        CostDiscovery(DiscoveryPostgresFixture f) throws Exception {
            this.f = f;
            f.snapshot("P02/P03 complete dataset before measured Discovery");
            costMetadata(f);
            long start = System.nanoTime();
            connection =
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                            f.target.source.getConnection(), true);
            long access = System.nanoTime() - start;
            meter = new WriteRevisionPerformancePostgresTest.Meter(connection);
            var owner =
                    new SpringAttemptTransactions(
                            meter, ConsumptionLifecyclePostgresFixture.jdbcManager(meter));
            var locks = new JdbcPartitionOwnershipStore(meter);
            var timing =
                    new DiscoveryPostgresFixture.LockMeasurements(
                            f, owner, meter, locks, f.requestReferences(meter));
            engine =
                    new DiscoveryEngine(
                            f.definition,
                            timing.transactions(),
                            timing.ownership(),
                            new JdbcDiscoveryAdmissionStore(meter, f.settings),
                            f.settings,
                            timing.requests());
            f.trace.add(
                    "P02/P03 cost harness pinned sequential JDBC owner SQL-only Discovery; actual"
                            + " nonowner runtime; initial connectionAccessNs="
                            + access
                            + ". Production shared JPA owner is exercised separately by"
                            + " concurrency/composition proofs; no I5 pool guarantee");
        }

        DiscoveryEngine.RequestProgress discover(
                PartitionLease lease, DiscoveryEngine.RequestWalk walk) throws Exception {
            meter.reset();
            meter.enabled = true;
            try {
                long submitted = System.nanoTime();
                return dispatcher
                        .submit(
                                () -> {
                                    long dispatch = System.nanoTime() - submitted;
                                    long allocation = allocatedBytes();
                                    try {
                                        return engine.discoverRequests(lease, walk);
                                    } finally {
                                        f.trace.add(
                                                "COST actual harness single-platform-thread"
                                                        + " dispatchNs="
                                                        + dispatch
                                                        + " executingThreadCumulativeAllocatedBytes="
                                                        + (allocatedBytes() - allocation)
                                                        + "; test dispatcher only, no production"
                                                        + " Worker/resource guarantee");
                                    }
                                })
                        .get(30, TimeUnit.SECONDS);
            } finally {
                meter.enabled = false;
                assertTrue(meter.statements > 0);
                f.trace.add(
                        "COST actual application JDBC statements="
                                + meter.statements
                                + " consumedRows="
                                + meter.rows
                                + " queries="
                                + meter.queries
                                + " includes test-only clock/PID measurement queries");
            }
        }

        public void close() {
            dispatcher.shutdownNow();
            connection.destroy();
        }
    }

    void dmlPlanRolledBack(DiscoveryPostgresFixture f) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = f.target.admin.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "explain (analyze,buffers,wal,format json) insert into"
                                + " consumption_slot(consumption_id,consumable_id,partition_id,partition_hash)"
                                + " values(?,?,mod(mod(consumption_hash_v1(?,?)::bigint,4)+4,4)::integer,consumption_hash_v1(?,?))")) {
                statement.setObject(1, f.definition.id().value());
                statement.setObject(2, id);
                statement.setString(3, f.family.name());
                statement.setObject(4, id);
                statement.setString(5, f.family.name());
                statement.setObject(6, id);
                try (var rows = statement.executeQuery()) {
                    while (rows.next())
                        f.trace.add(
                                "P DML EXPLAIN ANALYZE BUFFERS WAL disposable administrative probe"
                                        + " ROLLBACK: "
                                        + rows.getString(1));
                }
            } finally {
                connection.rollback();
            }
        }
        assertFalse(f.slotIds().contains(id));
    }

    @Test
    void i4_p02() throws Exception {
        for (int size : List.of(100, 1000, 10000))
            for (int page : List.of(1, 16, 32))
                both(
                        "keyset-cost-N" + size + "-B" + page,
                        f -> {
                            var localIds = input(f.family, 0).subList(0, size * 3 / 4);
                            var ids = new ArrayList<UUID>(localIds);
                            ids.addAll(input(f.family, 1).subList(0, size / 4));
                            ids.sort(Comparator.naturalOrder());
                            bulkPending(f, ids);
                            var existing = new ArrayList<UUID>();
                            for (int n = 0; n < localIds.size(); n++)
                                if (n % 5 != 4) existing.add(localIds.get(n));
                            f.trace.add(
                                    "P02 global mixed population 75% local / 25% outside partition;"
                                            + " N="
                                            + size
                                            + " independent Python inputs");
                            bulkSlots(f, existing, 25);
                            f.target.oracle.execute(
                                    "update consumption_slot set claimed_until=case when"
                                        + " mod(abs(partition_hash::bigint),2)=0 then"
                                        + " clock_timestamp()+interval '60 second' else"
                                        + " clock_timestamp()-interval '1 second'"
                                        + " end,claim_token=gen_random_uuid() where"
                                        + " state='AVAILABLE' and"
                                        + " mod(('x'||right(replace(consumable_id::text,'-',''),4))::bit(16)::int,3)=0");
                            f.target.oracle.execute(
                                    "update consumption_slot set"
                                            + " retry_after=clock_timestamp()+interval '60 second'"
                                            + " where state='AVAILABLE' and claim_token is null and"
                                            + " mod(abs(partition_hash::bigint),3)=1");
                            f.settings =
                                    new DiscoveryEngine.Settings(
                                            128,
                                            page,
                                            32,
                                            size + 64L,
                                            Duration.ofMillis(500),
                                            Duration.ofSeconds(10));
                            var lease = f.lease(0);
                            var walk = DiscoveryEngine.RequestWalk.fresh();
                            try (var cost = new CostDiscovery(f)) {
                                for (int sample = -5; sample < 10; sample++) {
                                    String walBefore = wal(f);
                                    long allocatedBefore = allocatedBytes();
                                    long start = System.nanoTime();
                                    var r = cost.discover(lease, walk);
                                    long ns = System.nanoTime() - start;
                                    long allocation = allocatedBytes() - allocatedBefore;
                                    long walBytes = walDelta(f, walBefore);
                                    assertTrue(r.examined() <= page);
                                    assertTrue(r.advanced() <= r.examined());
                                    assertTrue(r.committedAdmissions() <= 32);
                                    walk = r.next();
                                    f.trace.add(
                                            "P02 sample="
                                                    + sample
                                                    + " N="
                                                    + size
                                                    + " B="
                                                    + page
                                                    + " ns="
                                                    + ns
                                                    + " cumulativeClientAllocatedBytes="
                                                    + allocation
                                                    + " clusterWalUpperBoundBytes="
                                                    + walBytes
                                                    + " navigation="
                                                    + r
                                                    + " SQL stock="
                                                    + f.count());
                                }
                            }
                            dmlPlanRolledBack(f);
                            f.trace.add(
                                    "P02 keyset plan="
                                            + f.write.oracle.queryForList(
                                                    "explain (analyze,buffers,wal,format json)"
                                                            + " select "
                                                            + f.requestKey()
                                                            + " from "
                                                            + f.requestTable()
                                                            + " where status='PENDING' order by "
                                                            + f.requestKey()
                                                            + " limit "
                                                            + page));
                        });
        duplicateLockedCost();
        both(
                "cost-fully-filtered-page-before-local",
                f -> {
                    UUID other =
                            DiscoveryPostgresFixture.u(DiscoveryPostgresFixture.P1.get(f.family));
                    f.pending(other);
                    f.pending(f.q(0));
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 1, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    try (var cost = new CostDiscovery(f)) {
                        var first = cost.discover(lease, DiscoveryEngine.RequestWalk.fresh());
                        assertEquals(1, first.examined());
                        assertEquals(1, first.advanced());
                        assertEquals(0, first.committedAdmissions());
                        assertFalse(first.tourFinished());
                        var second = cost.discover(lease, first.next());
                        assertEquals(1, second.committedAdmissions());
                        assertTrue(second.tourFinished());
                        assertEquals(Set.of(f.q(0)), f.slotIds());
                        f.trace.add(
                                "filtered first page is global navigation; second real call admits"
                                        + " local key; outside source has no slot");
                    }
                });
    }

    @Test
    void i4_p03() throws Exception {
        for (int size : List.of(100, 1000, 10000))
            for (int percent : List.of(0, 50, 90))
                both(
                        "stock-cost-N" + size + "-Q" + percent,
                        f -> {
                            var ids = input(f.family, 0).subList(0, size);
                            bulkPending(f, ids);
                            bulkSlots(f, ids, percent);
                            long live = f.count();
                            assertEquals((long) size * (100 - percent) / 100, live);
                            f.pending(f.q(0));
                            f.settings =
                                    new DiscoveryEngine.Settings(
                                            128,
                                            32,
                                            32,
                                            live,
                                            Duration.ofMillis(500),
                                            Duration.ofSeconds(10));
                            var lease = f.lease(0);
                            try (var cost = new CostDiscovery(f)) {
                                for (int sample = -5; sample < 10; sample++) {
                                    String walBefore = wal(f);
                                    long allocatedBefore = allocatedBytes();
                                    long start = System.nanoTime();
                                    var r =
                                            cost.discover(
                                                    lease, DiscoveryEngine.RequestWalk.fresh());
                                    long ns = System.nanoTime() - start;
                                    long allocation = allocatedBytes() - allocatedBefore;
                                    long walBytes = walDelta(f, walBefore);
                                    assertEquals(DiscoveryEngine.Status.CAPACITY, r.status());
                                    assertEquals(0, r.examined());
                                    f.trace.add(
                                            "P03 COUNT N="
                                                    + size
                                                    + " Q="
                                                    + percent
                                                    + " sample="
                                                    + sample
                                                    + " ns="
                                                    + ns
                                                    + " cumulativeClientAllocatedBytes="
                                                    + allocation
                                                    + " clusterWalUpperBoundBytes="
                                                    + walBytes
                                                    + " stock="
                                                    + live
                                                    + " navigation="
                                                    + r);
                                }
                            }
                            dmlPlanRolledBack(f);
                            UUID free = ids.get(percent == 0 ? 0 : percent);
                            f.complete(lease, free);
                            var r =
                                    f.engine()
                                            .discoverRequests(
                                                    lease, DiscoveryEngine.RequestWalk.fresh());
                            assertEquals(1, r.committedAdmissions());
                            assertTrue(f.slotIds().contains(f.q(0)));
                            assertEquals(live, f.count());
                            f.trace.add(
                                    "P03 COUNT plan="
                                            + f.target.oracle.queryForList(
                                                    "explain (analyze,buffers,wal,format json)"
                                                        + " select count(*) from consumption_slot"
                                                        + " where consumption_id='"
                                                            + f.definition.id().value()
                                                            + "' and partition_id=0 and"
                                                            + " state<>'QUARANTINED'"));
                        });
        duplicateLockedCost();
        i4_b18();
    }

    Map<UUID, Integer> externalPartitions(ConsumableFamily family, List<UUID> ids)
            throws Exception {
        String program =
                "import sys,hashlib,uuid\n"
                    + "for line in sys.stdin:\n"
                    + " u=uuid.UUID(line.strip());h=hashlib.sha256(b'pocoma-consumable-v1\\0'+sys.argv[1].encode()+b'\\0'+u.bytes).digest();print(str(u),int.from_bytes(h[:4],'big',signed=True)%4)";
        var outputFile = java.nio.file.Files.createTempFile("i4-python-routing-", ".txt");
        var errorFile = java.nio.file.Files.createTempFile("i4-python-routing-error-", ".txt");
        String output;
        try {
            var process =
                    new ProcessBuilder("python3", "-c", program, family.name())
                            .redirectOutput(outputFile.toFile())
                            .redirectError(errorFile.toFile())
                            .start();
            try (var input = process.getOutputStream()) {
                input.write(
                        ids.stream()
                                .map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining("\n", "", "\n"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean resolved = process.waitFor(30, TimeUnit.SECONDS);
            if (!resolved) process.destroyForcibly();
            assertTrue(resolved, "independent Python oracle timeout");
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(errorFile));
            output = java.nio.file.Files.readString(outputFile);
        } finally {
            java.nio.file.Files.deleteIfExists(outputFile);
            java.nio.file.Files.deleteIfExists(errorFile);
        }
        var result = new LinkedHashMap<UUID, Integer>();
        for (String line : output.lines().toList()) {
            var fields = line.split(" ");
            result.put(UUID.fromString(fields[0]), Integer.parseInt(fields[1]));
        }
        return result;
    }

    void realWireEvents() throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            real(
                    "real-wire-" + fault,
                    f -> {
                        produceReal(f, 4);
                        var ids =
                                f.write.oracle.queryForList(
                                        "select event_id from v3_event_position where family=?"
                                                + " order by position",
                                        UUID.class,
                                        f.family == ConsumableFamily.POT_EVENT
                                                ? "POT"
                                                : "COMMAND_COMPLETION");
                        var routing = externalPartitions(f.family, ids);
                        int partition = routing.get(ids.getFirst());
                        f.trace.add("external Python routing=" + routing);
                        wire(f, f.lease(partition), fault);
                    });
    }

    void realStalePreparation() throws Exception {
        real(
                "real-stale-K",
                f -> {
                    produceReal(f, 4);
                    var lease = f.lease(0);
                    var prepared = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var events =
                            DiscoveryPostgresFixture.wrap(
                                    NonBurnableReferences.class,
                                    f.events,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("window")) {
                                            prepared.countDown();
                                            assertTrue(release.await(8, TimeUnit.SECONDS));
                                        }
                                    });
                    var second =
                            f.target.threads.submit(
                                    () ->
                                            f.engine(f.admissions, events, null, f.target.tx)
                                                    .discoverEvents(lease));
                    assertTrue(prepared.await(8, TimeUnit.SECONDS));
                    Set<UUID> before;
                    try {
                        assertEquals(
                                4, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                        before = f.slotIds();
                    } finally {
                        release.countDown();
                    }
                    assertEquals(
                            DiscoveryEngine.Status.STALE_CHECKPOINT,
                            second.get(8, TimeUnit.SECONDS).status());
                    assertEquals(before, f.slotIds());
                });
    }

    void realCompletionAndAck() throws Exception {
        for (boolean ack : List.of(false, true))
            real(
                    ack ? "real-finalization-ACK-lost" : "real-finalization-rollback",
                    f -> {
                        produceReal(f, 4);
                        String sequence =
                                f.family == ConsumableFamily.POT_EVENT
                                        ? "POT"
                                        : "COMMAND_COMPLETION";
                        var ids =
                                f.write.oracle.queryForList(
                                        "select event_id from v3_event_position where family=?"
                                                + " order by position",
                                        UUID.class,
                                        sequence);
                        var routing = externalPartitions(f.family, ids);
                        UUID id = ids.getFirst();
                        int partition = routing.get(id);
                        f.trace.add("external Python routing=" + routing);
                        var lease = f.lease(partition);
                        f.engine().discoverEvents(lease);
                        assertTrue(f.slotIds().contains(id));
                        if (!ack) {
                            var bad =
                                    new ConsumptionParticipant() {
                                        public Optional<Prepared> prepare(UUID request) {
                                            return f.participant
                                                    .prepare(request)
                                                    .map(
                                                            p ->
                                                                    new Prepared() {
                                                                        public void finalizeIn(
                                                                                SlotCompletion c) {
                                                                            p.finalizeIn(c);
                                                                            throw new CancellationException(
                                                                                    "controlled"
                                                                                        + " real-source"
                                                                                        + " READ"
                                                                                        + " rollback");
                                                                        }

                                                                        public void
                                                                                requireCompletionReady() {
                                                                            p
                                                                                    .requireCompletionReady();
                                                                        }
                                                                    });
                                        }

                                        public RecoveryObservation observe(
                                                UUID request, RecoveryReadContext c) {
                                            return f.participant.observe(request, c);
                                        }
                                    };
                            var e =
                                    new ConsumptionEngine(
                                            f.definition,
                                            f.target.tx,
                                            f.target.slots,
                                            bad,
                                            f.target.ownership,
                                            f.target.settings,
                                            f.capacity,
                                            c ->
                                                    AttemptFailureClassifier.FailureKind
                                                            .NON_COUNTABLE);
                            var claim = e.acquire(lease, List.of(id)).claims().getFirst();
                            assertEquals(
                                    ConsumptionEngine.Status.ROLLED_BACK,
                                    e.execute(claim).status());
                            assertEquals(0, f.target.effects(id));
                            assertTrue(f.slotIds().contains(id));
                            f.target.oracle.update(
                                    "update consumption_slot set"
                                            + " claimed_until=clock_timestamp()-interval '1 second'"
                                            + " where consumption_id=? and consumable_id=?",
                                    f.definition.id().value(),
                                    id);
                            f.complete(lease, id);
                        } else {
                            try (var proxy =
                                    new CommitAcknowledgementProxy(
                                            PG.getHost(), PG.getMappedPort(5432))) {
                                var source =
                                        new DriverManagerDataSource(
                                                "jdbc:postgresql://"
                                                        + PG.getHost()
                                                        + ":"
                                                        + proxy.localPort()
                                                        + "/"
                                                        + f.target.database
                                                        + "?currentSchema="
                                                        + f.target.schema
                                                        + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                                f.target.runtimeRole,
                                                "i3-fixture");
                                var owner =
                                        new SpringAttemptTransactions(
                                                source,
                                                ConsumptionLifecyclePostgresFixture.jdbcManager(
                                                        source));
                                var armed = new AtomicBoolean();
                                AttemptTransactions tx =
                                        new AttemptTransactions() {
                                            public <T> T read(
                                                    java.util.function.Function<
                                                                    RecoveryReadContext, T>
                                                            a) {
                                                return owner.read(a);
                                            }

                                            public <T> T write(java.util.function.Supplier<T> a) {
                                                if (armed.compareAndSet(true, false))
                                                    proxy.armed.set(
                                                            CommitAcknowledgementProxy.Fault
                                                                    .AFTER_COMMIT);
                                                return owner.write(a);
                                            }

                                            public void poison(Throwable t) {
                                                owner.poison(t);
                                            }

                                            public void requirePublicationClosed() {
                                                owner.requirePublicationClosed();
                                            }
                                        };
                                var e =
                                        new ConsumptionEngine(
                                                f.definition,
                                                tx,
                                                new JdbcConsumptionSlotStore(source),
                                                f.target.participantFor(source, owner),
                                                new JdbcPartitionOwnershipStore(source),
                                                f.target.settings,
                                                f.capacity,
                                                c ->
                                                        AttemptFailureClassifier.FailureKind
                                                                .NON_COUNTABLE);
                                var claim = e.acquire(lease, List.of(id)).claims().getFirst();
                                armed.set(true);
                                var result = e.execute(claim);
                                assertEquals(
                                        AttemptTransactions.Resolution.UNKNOWN,
                                        result.resolution());
                                assertEquals(1, proxy.committedReplies.get());
                                assertEquals(1, f.target.effects(id));
                                assertFalse(f.slotIds().contains(id));
                                f.trace.add(
                                        "READ finalization AFTER_COMMIT server ACK suppressed;"
                                                + " resolution historical UNKNOWN, independent"
                                                + " effect=1/slot absent");
                                e.settlePending();
                            }
                        }
                        assertEquals(1, f.target.effects(id));
                        assertFalse(f.slotIds().contains(id));
                        assertEquals(4, f.k(partition));
                        assertEquals(0, f.engine().discoverEvents(lease).inserted());
                        assertFalse(f.slotIds().contains(id));
                    });
    }

    void exactCapAndBatchVariants() throws Exception {
        for (boolean stock63 : List.of(false, true))
            five(
                    stock63 ? "stock63-cap64" : "batch1-with-cap64-margin",
                    f -> {
                        var ids = four(f);
                        if (stock63)
                            for (UUID id : input(f.family, 0).subList(0, 63)) f.slot(id, false);
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        128,
                                        32,
                                        stock63 ? 32 : 1,
                                        64,
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(2));
                        var lease = f.lease(0);
                        long stock = f.count();
                        assertEquals(stock63 ? 63 : 0, stock);
                        var first = f.engine().discoverEvents(lease);
                        assertEquals(2, first.checkpoint().orElseThrow());
                        assertEquals(1, first.inserted());
                        assertEquals(stock + 1, f.count());
                        assertFalse(f.slotIds().contains(ids.get(2)));
                        f.complete(lease, ids.get(0));
                        assertEquals(
                                4, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                        assertTrue(f.slotIds().contains(ids.get(2)));
                    });
    }

    void noRelevantIdentityEvents() throws Exception {
        scenario(
                ConsumableFamily.IDENTITY_EVENT,
                false,
                "no-EUI-only-User",
                f -> {
                    four(f);
                    f.write.oracle.update(
                            "update fixture_discovery_event set subject='POCOMA_USER'");
                    var result = f.engine().discoverEvents(f.lease(0));
                    assertEquals(4, result.checkpoint().orElseThrow());
                    assertEquals(0, result.inserted());
                    assertTrue(f.slotIds().isEmpty());
                });
    }

    void missingEnvelopeAndSnapshot() throws Exception {
        real(
                "missing-real-envelope",
                f -> {
                    produceReal(f, 1);
                    String sequence =
                            f.family == ConsumableFamily.POT_EVENT ? "POT" : "COMMAND_COMPLETION";
                    UUID missing = UUID.fromString("04000000-0000-0000-0000-000000000017");
                    try (var c = f.write.admin.getConnection()) {
                        c.setAutoCommit(false);
                        try (var q =
                                c.prepareStatement(
                                        "update v3_event_counter set position=position+1 where"
                                                + " family=? returning position")) {
                            q.setString(1, sequence);
                            try (var row = q.executeQuery()) {
                                row.next();
                                try (var st =
                                        c.prepareStatement(
                                                "insert into v3_event_position values(?,?,?)")) {
                                    st.setString(1, sequence);
                                    st.setLong(2, row.getLong(1));
                                    st.setObject(3, missing);
                                    st.executeUpdate();
                                }
                            }
                        }
                        c.commit();
                    }
                    assertThrows(
                            RuntimeException.class, () -> f.engine().discoverEvents(f.lease(0)));
                    assertEquals(0, f.k(0));
                    assertTrue(f.slotIds().isEmpty());
                });
        scenario(
                ConsumableFamily.POT_EVENT,
                false,
                "publication-between-RR-SELECTs",
                f -> {
                    four(f);
                    var lease = f.lease(0);
                    var once = new AtomicBoolean();
                    var source =
                            DiscoveryPostgresFixture.wrap(
                                    NonBurnableReferences.class,
                                    f.events,
                                    (m, a) -> {
                                        if (m.equals("window") && once.compareAndSet(false, true)) {
                                            assertEquals(
                                                    4,
                                                    f.write.sql.queryForObject(
                                                            "select position from"
                                                                    + " fixture_discovery_counter",
                                                            Integer.class));
                                            f.write
                                                    .threads
                                                    .submit(
                                                            () -> {
                                                                f.fixtureEvents(List.of(f.q(2)));
                                                                return null;
                                                            })
                                                    .get(8, TimeUnit.SECONDS);
                                        }
                                    },
                                    (m, a) -> {});
                    assertEquals(
                            4,
                            f.engine(f.admissions, source, null, f.target.tx)
                                    .discoverEvents(lease)
                                    .checkpoint()
                                    .orElseThrow());
                    assertFalse(f.slotIds().contains(f.q(2)));
                    assertEquals(5, f.engine().discoverEvents(lease).checkpoint().orElseThrow());
                    assertTrue(f.slotIds().contains(f.q(2)));
                });
    }

    void nearCapConcurrentRequests() throws Exception {
        both(
                "same-token-race-one-remaining-place",
                f -> {
                    f.pending(f.q(0));
                    f.pending(f.q(1));
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(10));
                    var lease = f.lease(0);
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var once = new AtomicBoolean();
                    var store =
                            DiscoveryPostgresFixture.wrap(
                                    DiscoveryAdmissionStore.class,
                                    f.admissions,
                                    (m, a) -> {},
                                    (m, a) -> {
                                        if (m.equals("insertInitial")
                                                && once.compareAndSet(false, true)) {
                                            held.countDown();
                                            assertTrue(release.await(8, TimeUnit.SECONDS));
                                        }
                                    });
                    var first =
                            f.target.threads.submit(
                                    () ->
                                            f.engine(store, null, f.requests, f.target.tx)
                                                    .discoverRequests(
                                                            lease,
                                                            DiscoveryEngine.RequestWalk.fresh()));
                    assertTrue(held.await(8, TimeUnit.SECONDS));
                    try {
                        assertEquals(
                                DiscoveryEngine.Status.BUSY_OR_NOT_OWNER,
                                f.engine()
                                        .discoverRequests(
                                                lease, DiscoveryEngine.RequestWalk.fresh())
                                        .status());
                    } finally {
                        release.countDown();
                    }
                    var result = first.get(8, TimeUnit.SECONDS);
                    assertEquals(1, result.committedAdmissions());
                    assertEquals(1, f.count());
                    assertEquals(
                            DiscoveryEngine.Status.CAPACITY,
                            f.engine()
                                    .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                                    .status());
                    f.complete(lease, f.q(0));
                    assertEquals(
                            1,
                            f.engine()
                                    .discoverRequests(lease, result.next())
                                    .committedAdmissions());
                    assertEquals(Set.of(f.q(1)), f.slotIds());
                });
    }

    void lockedAtInitialCapacity() throws Exception {
        both(
                "initial-capacity-with-protected-source",
                f -> {
                    for (int n = 0; n < 3; n++) f.pending(f.q(n));
                    f.slot(f.q(0), false);
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 32, 32, 1, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var walk = DiscoveryEngine.RequestWalk.fresh();
                    try (var locked = f.lockSource(f.q(1))) {
                        var result = f.engine().discoverRequests(lease, walk);
                        assertEquals(0, result.examined());
                        assertEquals(walk, result.next());
                        f.complete(lease, f.q(0));
                        locked.rollback();
                    }
                    var first = f.engine().discoverRequests(lease, walk);
                    assertEquals(1, first.committedAdmissions());
                    f.complete(lease, f.q(1));
                    assertEquals(
                            1,
                            f.engine().discoverRequests(lease, first.next()).committedAdmissions());
                    assertEquals(Set.of(f.q(2)), f.slotIds());
                });
    }

    void arrivalsWithSkippedReference() throws Exception {
        both(
                "arrivals-and-skipped-then-free",
                f -> {
                    f.pending(f.q(1));
                    f.pending(f.q(3));
                    f.settings =
                            new DiscoveryEngine.Settings(
                                    128, 1, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
                    var lease = f.lease(0);
                    var r = f.engine().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    for (UUID id : List.of(f.q(0), f.q(2), f.q(4))) f.pending(id);
                    try (var locked = f.lockSource(f.q(2))) {
                        var skipped = f.engine().discoverRequests(lease, r.next());
                        assertEquals(0, skipped.committedAdmissions());
                        assertEquals(Optional.of(f.q(2)), skipped.next().afterExclusive());
                        assertFalse(skipped.tourFinished());
                        r = f.engine().discoverRequests(lease, skipped.next());
                        assertTrue(r.tourFinished());
                        assertFalse(f.slotIds().contains(f.q(2)));
                        locked.rollback();
                    }
                    int calls = 0;
                    do {
                        r = f.engine().discoverRequests(lease, r.next());
                        assertTrue(++calls <= 6);
                        f.trace.add("controlled free tour opportunity " + r);
                    } while (!r.tourFinished());
                    assertEquals(Set.of(f.q(0), f.q(1), f.q(2), f.q(3), f.q(4)), f.slotIds());
                });
    }

    void checkpointRelationWait() throws Exception {
        for (boolean timeout : List.of(false, true))
            scenario(
                    ConsumableFamily.POT_EVENT,
                    false,
                    timeout ? "checkpoint-relation-timeout" : "checkpoint-post-wait-expiry",
                    f -> {
                        four(f);
                        var lease = f.lease(0);
                        f.settings =
                                new DiscoveryEngine.Settings(
                                        128,
                                        32,
                                        32,
                                        64,
                                        timeout ? Duration.ofMillis(200) : Duration.ofSeconds(5),
                                        Duration.ofSeconds(10));
                        var deadline =
                                f.target
                                        .oracle
                                        .queryForObject(
                                                "update partition_ownership set"
                                                    + " owned_until=clock_timestamp()+interval '1"
                                                    + " second' where consumption_id=? and"
                                                    + " partition_id=0 returning owned_until",
                                                Timestamp.class,
                                                f.definition.id().value())
                                        .toInstant();
                        var entered = new CountDownLatch(1);
                        var blocker = new AtomicReference<Connection>();
                        var pid = new AtomicInteger();
                        var store =
                                DiscoveryPostgresFixture.wrap(
                                        DiscoveryAdmissionStore.class,
                                        f.admissions,
                                        (m, a) -> {
                                            if (m.equals("advanceCheckpoint")) {
                                                var c = f.target.admin.getConnection();
                                                c.setAutoCommit(false);
                                                try (var st = c.createStatement()) {
                                                    st.execute(
                                                            "lock table discovery_checkpoint in"
                                                                    + " share mode");
                                                }
                                                blocker.set(c);
                                                pid.set(
                                                        f.target.sql.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                entered.countDown();
                                            }
                                        },
                                        (m, a) -> {});
                        var d =
                                f.target.threads.submit(
                                        () ->
                                                assertThrows(
                                                        AttemptTransactions.Failure.class,
                                                        () ->
                                                                f.engine(
                                                                                store,
                                                                                f.events,
                                                                                null,
                                                                                f.target.tx)
                                                                        .discoverEvents(lease)));
                        assertTrue(entered.await(8, TimeUnit.SECONDS));
                        try {
                            f.target.blocked(pid.get());
                            f.locks("D checkpoint CAS waits after all slots inserted");
                            if (!timeout) {
                                f.target.await(() -> f.target.now().isAfter(deadline));
                                blocker.get().rollback();
                            }
                            assertEquals(
                                    AttemptTransactions.Resolution.ROLLED_BACK,
                                    d.get(8, TimeUnit.SECONDS).resolution());
                        } finally {
                            if (blocker.get() != null) {
                                blocker.get().rollback();
                                blocker.get().close();
                            }
                        }
                        assertEquals(0, f.k(0));
                        assertTrue(f.slotIds().isEmpty());
                    });
    }

    ConsumptionParticipant independentParticipant(
            DiscoveryPostgresFixture f, ConsumptionId consumption, boolean fail) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id) {
                return Optional.of(
                        new Prepared() {
                            public void finalizeIn(SlotCompletion completion) {
                                SpringAttemptTransactions.guarded(
                                        f.target.source,
                                        () -> {
                                            f.target.sql.update(
                                                    "insert into fixture_effect_by_consumption"
                                                            + " values(?,?,'expected')",
                                                    consumption.value(),
                                                    id);
                                            return null;
                                        });
                                completion.complete();
                                SpringAttemptTransactions.closePublication(f.target.source);
                                if (fail)
                                    throw new CancellationException(
                                            "one independent READ finalization rolls back");
                            }

                            public void requireCompletionReady() {
                                assertEquals(
                                        1,
                                        f.target.sql.queryForObject(
                                                "select count(*) from fixture_effect_by_consumption"
                                                        + " where consumption_id=? and id=? and"
                                                        + " value='expected'",
                                                Integer.class,
                                                consumption.value(),
                                                id));
                            }
                        });
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                SpringAttemptTransactions.requireRead(f.target.source, c);
                return f.target.sql.queryForObject(
                                "select exists(select 1 from fixture_effect_by_consumption where"
                                        + " consumption_id=? and id=?)",
                                Boolean.class,
                                consumption.value(),
                                id)
                        ? RecoveryObservation.COMPLETED
                        : RecoveryObservation.NOT_COMPLETED;
            }
        };
    }

    void independentEffects(DiscoveryPostgresFixture f, List<UUID> consumptions) throws Exception {
        f.target.oracle.execute(
                "create table fixture_effect_by_consumption(consumption_id uuid,id uuid,value"
                        + " text,primary key(consumption_id,id));grant select,insert on"
                        + " fixture_effect_by_consumption to "
                        + f.target.runtimeRole);
        for (int n = 0; n < 3; n++) {
            var definition =
                    new ConsumptionDefinition(
                            new ConsumptionId(consumptions.get(n)),
                            f.family,
                            ConsumptionDefinition.Authority.READ);
            var row =
                    f.target.oracle.queryForMap(
                            "select owner_token,owned_until from partition_ownership where"
                                    + " consumption_id=? and partition_id=0",
                            definition.id().value());
            var lease =
                    new PartitionLease(
                            definition.id(),
                            0,
                            (UUID) row.get("owner_token"),
                            ((Timestamp) row.get("owned_until")).toInstant());
            if (n == 1) {
                var d =
                        new DiscoveryEngine(
                                definition,
                                f.target.tx,
                                f.target.ownership,
                                f.admissions,
                                f.settings,
                                f.write.tx,
                                f.events,
                                r -> true);
                assertEquals(2, d.discoverEvents(lease).inserted());
            }
            var capacity = new WorkerCapacity(2, Map.of(definition.id(), 2));
            var p = independentParticipant(f, definition.id(), n == 2);
            var e =
                    new ConsumptionEngine(
                            definition,
                            f.target.tx,
                            f.target.slots,
                            p,
                            f.target.ownership,
                            f.target.settings,
                            capacity,
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var claim = e.acquire(lease, List.of(f.q(0))).claims().getFirst();
            var result = e.execute(claim);
            assertEquals(
                    n == 2
                            ? ConsumptionEngine.Status.ROLLED_BACK
                            : ConsumptionEngine.Status.COMMITTED,
                    result.status());
            assertEquals(
                    n == 2 ? 0 : 1,
                    f.target.oracle.queryForObject(
                            "select count(*) from fixture_effect_by_consumption where"
                                    + " consumption_id=?",
                            Integer.class,
                            definition.id().value()));
            if (n == 2) {
                assertEquals(
                        1,
                        f.target.oracle.queryForObject(
                                "select count(*) from consumption_slot where consumption_id=? and"
                                        + " consumable_id=?",
                                Integer.class,
                                definition.id().value(),
                                f.q(0)));
                f.target.oracle.update(
                        "update consumption_slot set claimed_until=clock_timestamp()-interval '1"
                                + " second' where consumption_id=? and consumable_id=?",
                        definition.id().value(),
                        f.q(0));
                var retry =
                        new ConsumptionEngine(
                                definition,
                                f.target.tx,
                                f.target.slots,
                                independentParticipant(f, definition.id(), false),
                                f.target.ownership,
                                f.target.settings,
                                capacity,
                                c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                assertEquals(
                        ConsumptionEngine.Status.COMMITTED,
                        retry.execute(retry.acquire(lease, List.of(f.q(0))).claims().getFirst())
                                .status());
            }
        }
        assertEquals(
                3,
                f.target.oracle.queryForObject(
                        "select count(*) from fixture_effect_by_consumption where id=?",
                        Integer.class,
                        f.q(0)));
        assertEquals(
                3,
                f.target.oracle.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        f.q(1)));
        f.trace.add(
                "Three independent technical effects for the same POT event committed in distinct"
                    + " READ units; one actual F rollback retained only its own slot before normal"
                    + " reacquisition");
    }

    long allocatedBytes() {
        var bean =
                (com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    String wal(DiscoveryPostgresFixture f) {
        return f.target.oracle.queryForObject(
                "select pg_current_wal_insert_lsn()::text", String.class);
    }

    long walDelta(DiscoveryPostgresFixture f, String before) {
        return f.target
                .oracle
                .queryForObject(
                        "select pg_wal_lsn_diff(pg_current_wal_insert_lsn(),?::pg_lsn)",
                        java.math.BigDecimal.class,
                        before)
                .longValueExact();
    }
}
