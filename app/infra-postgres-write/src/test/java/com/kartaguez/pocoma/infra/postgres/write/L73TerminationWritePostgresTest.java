package com.kartaguez.pocoma.infra.postgres.write;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreatePot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Eui;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdatePotDetails;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.Outcome;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.Result;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.write.CommandExecutionPostgresTest.FixtureAuthority;
import com.kartaguez.pocoma.infra.postgres.write.L1CommandCompatibilityHarness.RetryPolicy;
import com.kartaguez.pocoma.infra.postgres.write.L1CommandCompatibilityHarness.Status;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Testcontainers
class L73TerminationWritePostgresTest {
    private static final List<String> FAMILIES =
            List.of("POT", "COMMAND_COMPLETION", "INVITATION", "IDENTITY", "IDENTITY_COMPLETION");

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private SpringAttemptTransactions tx;
    private L1CommandStore.Fixture store;
    private JdbcPotHistoryAdapter history;
    private JdbcPotRevisionAdapter revisions;
    private FixtureAuthority authority;
    private String schema;
    private ExecutorService threads;

    private final Eui eui = new Eui("test", "creator");
    private final UserId creator = new UserId(UUID.randomUUID());
    private final PotId potId = new PotId(UUID.randomUUID());

    @BeforeEach
    void setup() {
        threads = Executors.newVirtualThreadPerTaskExecutor();
        schema = "v3_l73w_" + UUID.randomUUID().toString().replace("-", "");
        source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl()
                                + "&currentSchema="
                                + schema
                                + "&socketTimeout=20&options=-c%20lock_timeout=10000%20-c%20statement_timeout=20000",
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        I2Schema.migrate(source, schema);
        jdbc.execute(
                "create table fixture_actor(issuer text,subject text,revision bigint not"
                        + " null,user_id uuid,token text,primary key(issuer,subject))");
        jdbc.update(
                "insert into fixture_actor values (?,?,1,?,?)",
                eui.issuer(),
                eui.subject(),
                creator.value(),
                "creator-token");
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        tx = new SpringAttemptTransactions(source, manager);
        store = new L1CommandStore.Fixture(source, new JdbcPotCommandStore(source, tx));
        history = new JdbcPotHistoryAdapter(source);
        revisions = new JdbcPotRevisionAdapter(source);
        authority = new CommandExecutionPostgresTest().new FixtureAuthority(source);
    }

    @AfterEach
    void teardown() throws Exception {
        threads.shutdownNow();
        assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** L2M-E01-E (Command UNCHANGED & REJECTED): only COMMAND_COMPLETION advances. */
    @Test
    void l2m_e01_e_commandUnchangedAndRejectedOnlyAdvanceCompletion() {
        assertEquals(Outcome.CHANGED, run(new CreatePot(new Label("P"))).outcome());
        var baselineCounters = counters();
        var baselinePositions = positionCounts();
        assertEquals(1L, baselineCounters.get("POT"));
        assertEquals(1L, baselineCounters.get("COMMAND_COMPLETION"));
        assertEquals(0L, baselineCounters.get("INVITATION"));
        assertEquals(0L, baselineCounters.get("IDENTITY"));
        assertEquals(0L, baselineCounters.get("IDENTITY_COMPLETION"));

        // 1. UNCHANGED command
        assertEquals(Outcome.UNCHANGED, run(1, new UpdatePotDetails(new Label("P"))).outcome());
        var afterUnchangedCounters = counters();
        var afterUnchangedPositions = positionCounts();
        assertEquals(
                baselineCounters.get("COMMAND_COMPLETION") + 1,
                afterUnchangedCounters.get("COMMAND_COMPLETION"));
        assertEquals(
                baselinePositions.get("COMMAND_COMPLETION") + 1,
                afterUnchangedPositions.get("COMMAND_COMPLETION"));
        for (String family : List.of("POT", "INVITATION", "IDENTITY", "IDENTITY_COMPLETION")) {
            assertEquals(baselineCounters.get(family), afterUnchangedCounters.get(family), family);
            assertEquals(
                    baselinePositions.get(family), afterUnchangedPositions.get(family), family);
        }

        // 2. REJECTED command (CreatePot on already-existing potId)
        assertEquals(Outcome.REJECTED, run(0, new CreatePot(new Label("Q"))).outcome());
        var afterRejectedCounters = counters();
        var afterRejectedPositions = positionCounts();
        assertEquals(
                afterUnchangedCounters.get("COMMAND_COMPLETION") + 1,
                afterRejectedCounters.get("COMMAND_COMPLETION"));
        assertEquals(
                afterUnchangedPositions.get("COMMAND_COMPLETION") + 1,
                afterRejectedPositions.get("COMMAND_COMPLETION"));
        for (String family : List.of("POT", "INVITATION", "IDENTITY", "IDENTITY_COMPLETION")) {
            assertEquals(
                    afterUnchangedCounters.get(family), afterRejectedCounters.get(family), family);
            assertEquals(
                    afterUnchangedPositions.get(family),
                    afterRejectedPositions.get(family),
                    family);
        }
        assertDense("COMMAND_COMPLETION");
        assertDense("POT");
    }

    /** L2M-E01-G-INV-COMP: forced lock order INVITATION -> COMMAND_COMPLETION, commit & rollback. */
    @Test
    void l2m_e01_g_invComp_lockOrderCommitAndRollback() throws Exception {
        verifyLockOrder("INVITATION", "COMMAND_COMPLETION", true);
        verifyLockOrder("INVITATION", "COMMAND_COMPLETION", false);
    }

    /** L2M-E01-G-ID-IDCOMP: forced lock order IDENTITY -> IDENTITY_COMPLETION, commit & rollback. */
    @Test
    void l2m_e01_g_idIdComp_lockOrderCommitAndRollback() throws Exception {
        verifyLockOrder("IDENTITY", "IDENTITY_COMPLETION", true);
        verifyLockOrder("IDENTITY", "IDENTITY_COMPLETION", false);
    }

    /** L2M-CON-05-NOPURGE: isolated DELETE on consumption_slot refused under runtime roles & store guard. */
    @Test
    void l2m_con_05_nopurge_isolatedDeleteRefusedUnderRuntimeRolesAndGuard() throws Exception {
        String runtime = schema + "_rt";
        String react = schema + "_ra";
        String adm = schema + "_adm";
        jdbc.execute("create role " + runtime + " login password 'fixture' noinherit");
        jdbc.execute("create role " + react + " login password 'fixture' noinherit");
        jdbc.execute("create role " + adm + " login password 'fixture' noinherit");

        for (String script : List.of("grant-l2-lifecycle.sql", "grant-l2-discovery.sql")) {
            POSTGRES.copyFileToContainer(
                    MountableFile.forHostPath(
                            Path.of("../../scripts/v3/" + script).toAbsolutePath()),
                    "/tmp/" + script);
            var res =
                    POSTGRES.execInContainer(
                            "env",
                            "PGOPTIONS=-c search_path=" + schema,
                            "psql",
                            "-X",
                            "-U",
                            POSTGRES.getUsername(),
                            "-d",
                            POSTGRES.getDatabaseName(),
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-v",
                            "authority=WRITE",
                            "-v",
                            "expected_database=" + POSTGRES.getDatabaseName(),
                            "-v",
                            "expected_schema=" + schema,
                            "-v",
                            "expected_admin=" + POSTGRES.getUsername(),
                            "-v",
                            "runtime_role=" + runtime,
                            "-v",
                            "reactivation_role=" + react,
                            "-f",
                            "/tmp/" + script);
            assertEquals(0, res.getExitCode(), res.getStderr());
        }

        POSTGRES.copyFileToContainer(
                MountableFile.forHostPath(
                        Path.of("../../scripts/v3/grant-l5-admission.sql").toAbsolutePath()),
                "/tmp/grant-l5-admission.sql");
        var admRes =
                POSTGRES.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + schema,
                        "psql",
                        "-X",
                        "-U",
                        POSTGRES.getUsername(),
                        "-d",
                        POSTGRES.getDatabaseName(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "expected_database=" + POSTGRES.getDatabaseName(),
                        "-v",
                        "expected_schema=" + schema,
                        "-v",
                        "expected_admin=" + POSTGRES.getUsername(),
                        "-v",
                        "admission_role=" + adm,
                        "-f",
                        "/tmp/grant-l5-admission.sql");
        assertEquals(0, admRes.getExitCode(), admRes.getStderr());

        UUID cmdId = UUID.randomUUID();
        admitRaw(new PotCommand(cmdId, eui, "creator-token", potId, 0L, new CreatePot(new Label("P"))));
        UUID consumptionId = UUID.fromString("98f7268d-d340-581c-b83d-010926223e14");

        // 1. Admission role cannot DELETE consumption_slot (42501)
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        var admDs = new DriverManagerDataSource(url, adm, "fixture");
        try (var c = admDs.getConnection();
                var s = c.createStatement()) {
            var ex =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    s.executeUpdate(
                                            "delete from consumption_slot where consumable_id='"
                                                    + cmdId
                                                    + "'"));
            assertEquals("42501", ex.getSQLState());
        }

        // 2. Reactivation role cannot DELETE consumption_slot (42501)
        var reactDs = new DriverManagerDataSource(url, react, "fixture");
        try (var c = reactDs.getConnection();
                var s = c.createStatement()) {
            var ex =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    s.executeUpdate(
                                            "delete from consumption_slot where consumable_id='"
                                                    + cmdId
                                                    + "'"));
            assertEquals("42501", ex.getSQLState());
        }

        // 3. Runtime role using JdbcConsumptionSlotStore.deleteProtected refuses wrong claimToken & QUARANTINED
        var rtDs = new DriverManagerDataSource(url, runtime, "fixture");
        var rtTm = new DataSourceTransactionManager(rtDs);
        rtTm.setNestedTransactionAllowed(false);
        var rtTx = new SpringAttemptTransactions(rtDs, rtTm);
        var slotStore = new JdbcConsumptionSlotStore(rtDs);
        var ex1 =
                assertThrows(
                        com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure.class,
                        () ->
                                rtTx.write(
                                        () -> {
                                            slotStore.deleteProtected(
                                                    new ClaimedSlot(
                                                            new ConsumptionId(consumptionId),
                                                            cmdId,
                                                            UUID.randomUUID()));
                                            return null;
                                        }));
        assertInstanceOf(IllegalStateException.class, ex1.getCause());
        jdbc.update(
                "update consumption_slot set state='QUARANTINED',claim_token=null,claimed_until=null,retry_after=null where consumable_id=?",
                cmdId);
        var ex2 =
                assertThrows(
                        com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure.class,
                        () ->
                                rtTx.write(
                                        () -> {
                                            slotStore.deleteProtected(
                                                    new ClaimedSlot(
                                                            new ConsumptionId(consumptionId),
                                                            cmdId,
                                                            UUID.randomUUID()));
                                            return null;
                                        }));
        assertInstanceOf(IllegalStateException.class, ex2.getCause());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        cmdId));
    }

    private void verifyLockOrder(String firstFamily, String secondFamily, boolean commitFirst)
            throws Exception {
        long startFirst = counters().get(firstFamily);
        long startSecond = counters().get(secondFamily);
        var t1LockedFirst = new CountDownLatch(1);
        var t1ProceedSecond = new CountDownLatch(1);
        var t1Pid = new AtomicInteger();
        var t2Pid = new AtomicInteger();

        Future<?> f1 =
                threads.submit(
                        () -> {
                            var ds1 =
                                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                                            POSTGRES.getUsername(),
                                            POSTGRES.getPassword(),
                                            true);
                            try {
                                var j1 = new JdbcTemplate(ds1);
                                var tm = new DataSourceTransactionManager(ds1);
                                var tpl = new TransactionTemplate(tm);
                                tpl.execute(
                                        status -> {
                                            t1Pid.set(
                                                    j1.queryForObject(
                                                            "select pg_backend_pid()",
                                                            Integer.class));
                                            j1.queryForObject(
                                                    "select position from v3_event_counter where"
                                                            + " family=? for update",
                                                    Long.class,
                                                    firstFamily);
                                            t1LockedFirst.countDown();
                                            try {
                                                assertTrue(
                                                        t1ProceedSecond.await(
                                                                10, TimeUnit.SECONDS));
                                            } catch (InterruptedException e) {
                                                throw new RuntimeException(e);
                                            }
                                            j1.queryForObject(
                                                    "select position from v3_event_counter where"
                                                            + " family=? for update",
                                                    Long.class,
                                                    secondFamily);
                                            if (!commitFirst) {
                                                status.setRollbackOnly();
                                            }
                                            return null;
                                        });
                            } finally {
                                t1LockedFirst.countDown();
                                ds1.destroy();
                            }
                        });

        assertTrue(t1LockedFirst.await(10, TimeUnit.SECONDS));

        Future<?> f2 =
                threads.submit(
                        () -> {
                            var ds2 =
                                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                                            POSTGRES.getUsername(),
                                            POSTGRES.getPassword(),
                                            true);
                            try {
                                var j2 = new JdbcTemplate(ds2);
                                var tm = new DataSourceTransactionManager(ds2);
                                var tpl = new TransactionTemplate(tm);
                                tpl.execute(
                                        status -> {
                                            t2Pid.set(
                                                    j2.queryForObject(
                                                            "select pg_backend_pid()",
                                                            Integer.class));
                                            j2.queryForObject(
                                                    "select position from v3_event_counter where"
                                                            + " family=? for update",
                                                    Long.class,
                                                    firstFamily);
                                            j2.queryForObject(
                                                    "select position from v3_event_counter where"
                                                            + " family=? for update",
                                                    Long.class,
                                                    secondFamily);
                                            return null;
                                        });
                            } finally {
                                ds2.destroy();
                            }
                        });

        // Observe pg_blocking_pids attesting T1 blocks T2 on firstFamily
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
            if (f2.isDone()) {
                f2.get();
            }
            if (f1.isDone()) {
                f1.get();
            }
            if (t2Pid.get() != 0
                    && Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "select ?=any(pg_blocking_pids(?))",
                                    Boolean.class,
                                    t1Pid.get(),
                                    t2Pid.get()))) {
                blocked = true;
                break;
            }
            Thread.sleep(15);
        }
        assertTrue(blocked, "Expected T1 to block T2 on " + firstFamily);

        t1ProceedSecond.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertEquals(startFirst, counters().get(firstFamily));
        assertEquals(startSecond, counters().get(secondFamily));
    }

    private Result run(PotCommand.Intent intent) {
        return run(0, intent);
    }

    private Result run(long observed, PotCommand.Intent intent) {
        var cmd = new PotCommand(UUID.randomUUID(), eui, "creator-token", potId, observed, intent);
        admitRaw(cmd);
        UUID token = acquire(cmd.requestId());
        var engine =
                new L1CommandCompatibilityHarness(
                        tx,
                        store,
                        authority,
                        history,
                        revisions,
                        new RetryPolicy(3, Duration.ZERO),
                        Clock.systemUTC());
        var attempt = engine.execute(cmd.requestId(), token);
        assertEquals(Status.COMPLETED, attempt.status(), attempt.toString());
        return attempt.result().orElseThrow();
    }

    private void admitRaw(PotCommand command) {
        jdbc.update(
                "with cmd as (insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values (?,?,?,?,?,?,?,?::jsonb) returning request_id), pos as (update"
                        + " v3_request_counter set position=position+1 where family='COMMAND'"
                        + " returning position) insert into"
                        + " v3_request_position(family,position,request_id) select"
                        + " 'COMMAND',pos.position,cmd.request_id from cmd,pos",
                command.requestId(),
                command.requester().issuer(),
                command.requester().subject(),
                command.bindingToken(),
                command.potId().value(),
                command.observedVersion(),
                command.intent().getClass().getSimpleName(),
                PotCommandJson.encode(command.intent()));
        jdbc.update(
                "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                        + " select '98f7268d-d340-581c-b83d-010926223e14',id,h,((h::bigint%4+4)%4)::int"
                        + " from (select id,consumption_hash_v1('COMMAND',id) h from (select ?::uuid"
                        + " id) x) y",
                command.requestId());
    }

    private UUID acquire(UUID request) {
        var token = UUID.randomUUID();
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            jdbc.queryForList(
                                    "select consumable_id from consumption_slot where"
                                            + " consumable_id=? for update",
                                    request);
                            assertEquals(
                                    1,
                                    jdbc.update(
                                            "update consumption_slot set"
                                                    + " claim_token=?,claimed_until=clock_timestamp()+interval"
                                                    + " '5 minutes' where consumable_id=? and"
                                                    + " state='AVAILABLE'",
                                            token,
                                            request));
                            return token;
                        });
    }

    private Map<String, Long> counters() {
        var map = new TreeMap<String, Long>();
        for (String f : FAMILIES) {
            map.put(
                    f,
                    jdbc.queryForObject(
                            "select position from v3_event_counter where family=?",
                            Long.class,
                            f));
        }
        return map;
    }

    private Map<String, Long> positionCounts() {
        var map = new TreeMap<String, Long>();
        for (String f : FAMILIES) {
            map.put(
                    f,
                    jdbc.queryForObject(
                            "select count(*) from v3_event_position where family=?",
                            Long.class,
                            f));
        }
        return map;
    }

    private void assertDense(String family) {
        var positions =
                jdbc.queryForList(
                        "select position from v3_event_position where family=? order by position",
                        Long.class,
                        family);
        for (int i = 0; i < positions.size(); i++) {
            assertEquals(i + 1L, positions.get(i));
        }
        assertEquals(
                (long) positions.size(),
                jdbc.queryForObject(
                        "select position from v3_event_counter where family=?",
                        Long.class,
                        family));
    }
}
