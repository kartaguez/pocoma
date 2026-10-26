package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Direct I2 Engine proofs; no compatibility cession. Oracles use a separate physical connection.
 */
@Testcontainers
class L2ManifestProof {
    @Container static final PostgreSQLContainer POSTGRES = CommandExecutionPostgresTest.POSTGRES;
    @Container static final PostgreSQLContainer HISTORY_POSTGRES = PotHistoryPostgresTest.POSTGRES;
    final CommandExecutionPostgresTest f = new CommandExecutionPostgresTest();
    final List<String> evidence = new CopyOnWriteArrayList<>();
    ClaimedSlot claim;
    PotCommand command;
    JdbcConsumptionSlotStore slots;
    ExecutePotCommand participant;
    ConsumptionDefinition definition;

    @BeforeEach
    void setup(TestInfo info) {
        f.setup();
        if (info.getTestMethod().orElseThrow().getName().startsWith("l2m_l1_")) return;
        f.base();
        command = f.command(2, new PotCommand.UpdatePotDetails(new Label("I2 expected label")));
        claim = L1CommandStore.claim(command.requestId(), f.admit(command));
        definition =
                new ConsumptionDefinition(
                        claim.consumptionId(),
                        ConsumableFamily.COMMAND,
                        ConsumptionDefinition.Authority.WRITE);
        slots = new JdbcConsumptionSlotStore(f.source);
        participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(f.source, f.tx),
                        new JdbcPotCommandObservation(f.source),
                        f.authority,
                        f.history,
                        f.revisions,
                        Clock.systemUTC());
        evidence.add(
                "fixture: pot="
                        + f.p
                        + ", request="
                        + command.requestId()
                        + ", expected V=3 label=I2 expected label; initial V=2; count=0");
    }

    @AfterEach
    void collect(TestInfo info) throws Exception {
        evidence.add("final vector=" + vector());
        var path =
                Path.of(
                        "target/v3-i2-evidence",
                        info.getTestMethod().orElseThrow().getName() + ".txt");
        Files.createDirectories(path.getParent());
        Files.write(path, evidence);
        f.collect();
    }

    ConsumptionEngine engine() {
        return engine(participant, slots, f.tx);
    }

    ConsumptionEngine engine(
            ConsumptionParticipant business,
            ConsumptionSlotStore technical,
            AttemptTransactions tx) {
        return new ConsumptionEngine(definition, tx, technical, business);
    }

    ConsumptionParticipant wrapped(Consumer<ConsumptionParticipant.Prepared> action) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id) {
                return participant
                        .prepare(id)
                        .map(
                                p ->
                                        new Prepared() {
                                            public void finalizeIn(SlotCompletion c) {
                                                currentCompletion.set(c);
                                                action.accept(p);
                                            }

                                            public void requireCompletionReady() {
                                                p.requireCompletionReady();
                                            }
                                        });
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                return participant.observe(id, c);
            }
        };
    }

    final ThreadLocal<SlotCompletion> currentCompletion = new ThreadLocal<>();

    Map<String, String> vector() throws Exception {
        var result = new TreeMap<String, String>();
        try (var c = f.source.getConnection()) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            for (String table :
                    List.of(
                            "v3_command",
                            "consumption_slot",
                            "v3_pot_identity",
                            "v3_pot_version",
                            "v3_pot_header",
                            "v3_pot_details",
                            "v3_shareholder_identity",
                            "v3_shareholder_header",
                            "v3_shareholder_details",
                            "v3_shareholder_weight",
                            "v3_expense_identity",
                            "v3_expense_header",
                            "v3_expense_details",
                            "v3_expense_financials",
                            "v3_expense_participant",
                            "v3_command_result",
                            "v3_pot_event",
                            "v3_pot_event_component",
                            "v3_command_completion_event",
                            "v3_event_counter",
                            "v3_event_position")) {
                try (var s = c.createStatement();
                        var rs =
                                s.executeQuery(
                                        "select coalesce(jsonb_agg(to_jsonb(t) order by"
                                                + " to_jsonb(t)::text),'[]')::text from "
                                                + table
                                                + " t")) {
                    rs.next();
                    result.put(table, rs.getString(1));
                }
            }
            c.rollback();
        }
        evidence.add("independent RR RO vector=" + result);
        return result;
    }

    void unchanged(Map<String, String> before, ConsumptionEngine.Attempt attempt) throws Exception {
        evidence.add("attempt=" + attempt);
        assertEquals(ConsumptionEngine.Status.ROLLED_BACK, attempt.status());
        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, attempt.resolution());
        assertEquals(before, vector());
    }

    void committed() {
        var result = engine().execute(claim);
        evidence.add("attempt=" + result);
        assertEquals(ConsumptionEngine.Status.COMMITTED, result.status(), result.toString());
        assertEquals(AttemptTransactions.Resolution.COMMITTED, result.resolution());
        assertEquals(
                3L,
                f.jdbc.queryForObject(
                        "select current_version from v3_pot_identity where pot_id=?",
                        Long.class,
                        f.p.value()));
        assertEquals(
                "I2 expected label",
                f.jdbc.queryForObject(
                        "select label from v3_pot_details where pot_id=? and active_to is null",
                        String.class,
                        f.p.value()));
        assertEquals(
                0L,
                f.jdbc.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Long.class,
                        claim.consumableId()));
        assertEquals(
                "CHANGED",
                f.jdbc.queryForObject(
                        "select outcome from v3_command_result where request_id=?",
                        String.class,
                        claim.consumableId()));
        assertEquals(
                1L,
                f.jdbc.queryForObject(
                        "select count(*) from v3_command_completion_event where subject_id=?",
                        Long.class,
                        claim.consumableId()));
        assertEquals(RecoveryObservation.COMPLETED, engine().observe(claim));
        f.dense();
    }

    @Test
    void l2m_r1_01() {
        committed();
    }

    @Test
    void l2m_r1_02() throws Exception {
        completionFault("missing");
    }

    @Test
    void l2m_r1_03() {
        committed();
    }

    @Test
    void l2m_r1_04() throws Exception {
        completionFault("early");
    }

    @Test
    void l2m_r1_05() throws Exception {
        completionFault("double");
    }

    @Test
    void l2m_r1_05_missing() throws Exception {
        completionFault("missing");
    }

    @Test
    void l2m_r1_05_double() throws Exception {
        completionFault("double");
    }

    @Test
    void l2m_r1_05_late() throws Exception {
        completionFault("late");
    }

    @Test
    void l2m_r1_05_wrong() throws Exception {
        completionFault("wrong");
    }

    @Test
    void l2m_r1_06() throws Exception {
        completionFault("postclose");
    }

    @Test
    void l2m_r1_07() throws Exception {
        boundary("after-delete");
    }

    @Test
    void l2m_c04_b() throws Exception {
        var before = vector();
        var wrong = new ClaimedSlot(claim.consumptionId(), claim.consumableId(), new UUID(99, 99));
        var result = engine().execute(wrong);
        assertEquals(ConsumptionEngine.Status.CLAIM_LOST, result.status());
        assertEquals(before, vector());
    }

    @Test
    void l2m_r1_09() throws Exception {
        singleXid();
    }

    @Test
    void l2m_r1_10() {
        var inbound =
                f.jdbc.queryForList(
                        "select conname from pg_constraint where contype='f' and"
                                + " confrelid='consumption_slot'::regclass");
        assertTrue(inbound.isEmpty());
        var catalog =
                f.jdbc.queryForList(
                        "select conname,contype,condeferrable,condeferred,pg_get_constraintdef(oid)"
                                + " def from pg_constraint where"
                                + " connamespace=current_schema()::regnamespace order by conname");
        evidence.add("catalog=" + catalog);
        assertEquals(
                3L,
                f.jdbc.queryForObject(
                        "select count(*) from pg_constraint where conname in"
                            + " ('v3_command_unit','v3_result_unit','v3_pot_event_request') and"
                            + " connamespace=current_schema()::regnamespace and condeferrable and"
                            + " condeferred",
                        Long.class));
    }

    void completionFault(String fault) throws Exception {
        evidence.add("completion injection=" + fault);
        var before = vector();
        var saved = new AtomicReference<SlotCompletion>();
        var business =
                wrapped(
                        p -> {
                            var completion = currentCompletion.get();
                            switch (fault) {
                                case "missing" -> p.finalizeIn(() -> {});
                                case "double" ->
                                        p.finalizeIn(
                                                () -> {
                                                    completion.complete();
                                                    assertThrows(
                                                            RuntimeException.class,
                                                            completion::complete);
                                                });
                                case "late" -> p.finalizeIn(() -> saved.set(completion));
                                case "early" -> {
                                    assertThrows(RuntimeException.class, completion::complete);
                                    p.finalizeIn(completion);
                                }
                                case "wrong" ->
                                        p.finalizeIn(
                                                () -> {
                                                    // A completion from another callback cannot
                                                    // delete the current slot.
                                                    assertThrows(
                                                            Exception.class,
                                                            () ->
                                                                    f.threads
                                                                            .submit(
                                                                                    completion
                                                                                            ::complete)
                                                                            .get(
                                                                                    5,
                                                                                    TimeUnit
                                                                                            .SECONDS));
                                                });
                                case "postclose" -> {
                                    p.finalizeIn(completion);
                                    assertThrows(
                                            RuntimeException.class,
                                            () ->
                                                    f.revisions.recordVersion(
                                                            f.p, 3, 4, Instant.now()));
                                }
                                default -> throw new AssertionError(fault);
                            }
                        });
        unchanged(before, engine(business, slots, f.tx).execute(claim));
        if (saved.get() != null) assertThrows(RuntimeException.class, saved.get()::complete);
        assertEquals(before, vector());
    }

    void boundary(String point) throws Exception {
        evidence.add("rollback injection=" + point);
        var before = vector();
        var technical =
                new ConsumptionSlotStore() {
                    public Protection protectFinal(ClaimedSlot c) {
                        return slots.protectFinal(c);
                    }

                    public void deleteProtected(ClaimedSlot c) {
                        if (point.equals("before-delete")) throw new IllegalStateException(point);
                        slots.deleteProtected(c);
                        if (point.equals("after-delete")) throw new IllegalStateException(point);
                    }

                    public SlotObservation observe(
                            ConsumptionId c, UUID id, RecoveryReadContext context) {
                        return slots.observe(c, id, context);
                    }
                };
        unchanged(before, engine(participant, technical, f.tx).execute(claim));
    }

    @Test
    void l2m_e02_f_04() throws Exception {
        boundary("before-delete");
    }

    @Test
    void l2m_e02_f_05() throws Exception {
        boundary("after-delete");
    }

    @Test
    void l2m_c06_e() throws Exception {
        for (boolean sql : List.of(false, true)) {
            var before = vector();
            var business =
                    wrapped(
                            p ->
                                    p.finalizeIn(
                                            () -> {
                                                try {
                                                    SpringAttemptTransactions.guarded(
                                                            f.source,
                                                            () -> {
                                                                if (sql)
                                                                    f.jdbc.execute("select 1/0");
                                                                throw new IllegalArgumentException(
                                                                        "caught application writer"
                                                                                + " failure");
                                                            });
                                                } catch (RuntimeException caught) {
                                                    evidence.add("caught=" + caught);
                                                }
                                                currentCompletion.get().complete();
                                            }));
            unchanged(before, engine(business, slots, f.tx).execute(claim));
        }
    }

    @Test
    void l2m_c06_i_effect() throws Exception {
        requiresNew(false);
    }

    @Test
    void l2m_c06_i_delete() throws Exception {
        requiresNew(true);
    }

    void requiresNew(boolean delete) throws Exception {
        var before = vector();
        var manager = new DataSourceTransactionManager(f.source);
        manager.setNestedTransactionAllowed(false);
        var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var business =
                wrapped(
                        p ->
                                p.finalizeIn(
                                        () -> {
                                            try {
                                                inner.execute(
                                                        s -> {
                                                            if (delete)
                                                                slots.deleteProtected(claim);
                                                            else
                                                                f.revisions.recordVersion(
                                                                        f.p, 2, 3, Instant.now());
                                                            return null;
                                                        });
                                            } catch (RuntimeException caught) {
                                                evidence.add(
                                                        "independent transaction refused="
                                                                + caught);
                                            }
                                            currentCompletion.get().complete();
                                        }));
        unchanged(before, engine(business, slots, f.tx).execute(claim));
    }

    @Test
    void l2m_c06_h() throws Exception {
        var before = vector();
        var other =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var wrong = new DataSourceTransactionManager(other);
        wrong.setNestedTransactionAllowed(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringAttemptTransactions(f.source, wrong));
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        f.tx.write(
                                () -> {
                                    try {
                                        f.tx.write(() -> 1);
                                    } catch (RuntimeException caught) {
                                    }
                                    return 1;
                                }));
        var manager = new DataSourceTransactionManager(f.source);
        manager.setNestedTransactionAllowed(false);
        var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        f.tx.write(
                                () -> {
                                    try {
                                        SpringAttemptTransactions.guarded(
                                                f.source, () -> nested.execute(s -> 1));
                                    } catch (RuntimeException caught) {
                                    }
                                    return 1;
                                }));
        new TransactionTemplate(manager)
                .execute(
                        s -> {
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () -> f.tx.write(() -> 1));
                            return null;
                        });
        assertEquals(before, vector());
    }

    void singleXid() {
        f.jdbc.execute(
                "create table fixture_i2_trace(seq bigint generated always as identity,tab text,op"
                        + " text,pid int,xid xid8)");
        f.jdbc.execute(
                "create function fixture_i2_trace_fn() returns trigger language plpgsql as $$ begin"
                    + " insert into fixture_i2_trace(tab,op,pid,xid)"
                    + " values(TG_TABLE_NAME,TG_OP,pg_backend_pid(),pg_current_xact_id()); return"
                    + " null; end $$");
        for (String table :
                List.of(
                        "v3_pot_identity",
                        "v3_pot_version",
                        "v3_pot_details",
                        "v3_command",
                        "v3_command_result",
                        "consumption_slot",
                        "v3_pot_event",
                        "v3_pot_event_component",
                        "v3_command_completion_event",
                        "v3_event_counter",
                        "v3_event_position",
                        "v3_command_probe"))
            f.jdbc.execute(
                    "create trigger fixture_i2_trace after insert or update or delete on "
                            + table
                            + " for each row execute function fixture_i2_trace_fn()");
        committed();
        var trace = f.jdbc.queryForList("select * from fixture_i2_trace order by seq");
        evidence.add("physical SQL trace=" + trace);
        assertEquals(
                1L,
                f.jdbc.queryForObject(
                        "select count(distinct xid) from fixture_i2_trace", Long.class));
        assertEquals(
                1L,
                f.jdbc.queryForObject(
                        "select count(distinct pid) from fixture_i2_trace", Long.class));
        int deletion = -1, firstCounter = -1;
        for (int i = 0; i < trace.size(); i++) {
            if (trace.get(i).get("tab").equals("consumption_slot")) {
                assertEquals("DELETE", trace.get(i).get("op"));
                deletion = i;
            }
            if (trace.get(i).get("tab").equals("v3_event_counter") && firstCounter < 0)
                firstCounter = i;
        }
        assertTrue(deletion >= 0 && firstCounter > deletion);
        assertTrue(
                trace.subList(firstCounter, trace.size()).stream()
                        .allMatch(
                                row ->
                                        List.of("v3_event_counter", "v3_event_position")
                                                .contains(row.get("tab"))));
    }

    @Test
    void l2m_c06_a() {
        var factory = f.entityManagerFactory(f.source);
        try {
            var manager =
                    new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            manager.setDataSource(f.source);
            manager.setNestedTransactionAllowed(false);
            f.tx = new SpringAttemptTransactions(f.source, manager);
            var original =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(f.source, f.tx),
                            new JdbcPotCommandObservation(f.source),
                            f.authority,
                            f.history,
                            f.revisions,
                            Clock.systemUTC());
            participant = original;
            var business =
                    wrapped(
                            p -> {
                                SpringAttemptTransactions.guarded(
                                        f.source,
                                        () -> {
                                            var em =
                                                    org.springframework.orm.jpa
                                                            .EntityManagerFactoryUtils
                                                            .getTransactionalEntityManager(
                                                                    factory.getObject());
                                            em.persist(
                                                    new com.kartaguez.pocoma.infra.persistence
                                                            .primary.jpa.v3commandfixture
                                                            .CommandProbe(987, "JPA prefix"));
                                            return null;
                                        });
                                p.finalizeIn(currentCompletion.get());
                            });
            // Keep the wrapper out of its own prepare delegation.
            var wrappedEngine = engine(business, slots, f.tx);
            f.jdbc.execute("create table fixture_i2_jpa_xid(tab text,xid xid8,pid int)");
            f.jdbc.execute(
                    "create function fixture_i2_jpa_xid_fn() returns trigger language plpgsql as $$"
                            + " begin insert into fixture_i2_jpa_xid"
                            + " values(TG_TABLE_NAME,pg_current_xact_id(),pg_backend_pid());return"
                            + " null;end $$");
            for (var table :
                    List.of(
                            "v3_pot_version",
                            "v3_pot_details",
                            "v3_command_result",
                            "v3_command",
                            "consumption_slot",
                            "v3_pot_event",
                            "v3_command_completion_event",
                            "v3_event_position",
                            "v3_command_probe"))
                f.jdbc.execute(
                        "create trigger fixture_i2_jpa_xid after insert or update or delete on "
                                + table
                                + " for each row execute function fixture_i2_jpa_xid_fn()");
            assertEquals(ConsumptionEngine.Status.COMMITTED, wrappedEngine.execute(claim).status());
            assertEquals(
                    1L,
                    f.jdbc.queryForObject(
                            "select count(distinct xid) from fixture_i2_jpa_xid", Long.class));
            assertEquals(
                    1L,
                    f.jdbc.queryForObject(
                            "select count(distinct pid) from fixture_i2_jpa_xid", Long.class));
            assertEquals(
                    1L,
                    f.jdbc.queryForObject(
                            "select count(*) from v3_command_probe where id=987", Long.class));
            evidence.add(
                    "JPA + every Command durable write XID/PID="
                            + f.jdbc.queryForList("select * from fixture_i2_jpa_xid"));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void l2m_r9_01() {
        assertEquals(RecoveryObservation.NOT_COMPLETED, engine().observe(claim));
    }

    @Test
    void l2m_r9_02() {
        var retained = new AtomicReference<RecoveryReadContext>();
        f.tx.read(
                context -> {
                    retained.set(context);
                    assertEquals(
                            "on",
                            f.jdbc.queryForObject("show transaction_read_only", String.class));
                    assertEquals(
                            "repeatable read",
                            f.jdbc.queryForObject("show transaction_isolation", String.class));
                    slots.observe(claim.consumptionId(), claim.consumableId(), context);
                    participant.observe(claim.consumableId(), context);
                    return null;
                });
        assertThrows(RuntimeException.class, retained.get()::requireActiveRead);
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        f.tx.read(
                                context -> {
                                    f.jdbc.execute(
                                            "insert into v3_command_probe values(777,'read-only"
                                                    + " violation')");
                                    return null;
                                }));
        assertEquals(
                0L,
                f.jdbc.queryForObject(
                        "select count(*) from v3_command_probe where id=777", Long.class));
    }

    @Test
    void l2m_r9_03_completed() {
        committed();
    }

    @Test
    void l2m_r9_03_notcompleted() {
        assertEquals(RecoveryObservation.NOT_COMPLETED, engine().observe(claim));
    }

    @Test
    void l2m_r9_03_inconsistent() {
        f.jdbc.update("delete from consumption_slot where consumable_id=?", claim.consumableId());
        assertEquals(RecoveryObservation.INCONSISTENT, engine().observe(claim));
    }

    @Test
    void l2m_r9_03_unavailable() {
        var unavailable =
                new ForwardingTransactions(f.tx) {
                    public <T> T read(Function<RecoveryReadContext, T> action) {
                        throw new IllegalStateException("connection lost");
                    }
                };
        assertEquals(
                RecoveryObservation.UNAVAILABLE,
                engine(participant, slots, unavailable).observe(claim));
    }

    @Test
    void l2m_r9_03() {
        assertEquals(4, RecoveryObservation.values().length);
    }

    @Test
    void l2m_r9_04() {
        l2m_r9_03_inconsistent();
    }

    @Test
    void l2m_r9_05() throws Exception {
        concurrentObservation(false);
    }

    @Test
    void l2m_r9_06() throws Exception {
        var before = vector();
        assertEquals(RecoveryObservation.NOT_COMPLETED, engine().observe(claim));
        assertEquals(before, vector());
    }

    @Test
    void l2m_r9_07() throws Exception {
        concurrentObservation(true);
    }

    @Test
    void l2m_r9_08() {
        l2m_r9_02();
    }

    @Test
    void l2m_r9_09() {
        committed();
        f.jdbc.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from (select"
                    + " consumption_hash_v1('COMMAND',?::uuid) h) x",
                claim.consumptionId().value(), claim.consumableId(), claim.consumableId());
        assertEquals(RecoveryObservation.INCONSISTENT, engine().observe(claim));
        assertEquals(
                1L,
                f.jdbc.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Long.class,
                        claim.consumableId()));
    }

    void concurrentObservation(boolean commitBetweenReads) throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var business =
                wrapped(
                        p -> {
                            p.finalizeIn(currentCompletion.get());
                            reached.countDown();
                            CommandExecutionPostgresTest.await(release);
                        });
        var pending = f.threads.submit(() -> engine(business, slots, f.tx).execute(claim));
        CommandExecutionPostgresTest.await(reached);
        assertEquals(RecoveryObservation.NOT_COMPLETED, engine().observe(claim));
        // Establish RR snapshot by technical SELECT, resolve writer, then read business in that
        // snapshot.
        try {
            f.tx.read(
                    context -> {
                        assertEquals(
                                ConsumptionSlotStore.SlotObservation.PRESENT,
                                slots.observe(
                                        claim.consumptionId(), claim.consumableId(), context));
                        if (commitBetweenReads) {
                            release.countDown();
                            assertEquals(
                                    ConsumptionEngine.Status.COMMITTED,
                                    CommandExecutionPostgresTest.get(pending).status());
                        }
                        assertEquals(
                                RecoveryObservation.NOT_COMPLETED,
                                participant.observe(claim.consumableId(), context));
                        return null;
                    });
        } finally {
            release.countDown();
        }
        assertEquals(
                ConsumptionEngine.Status.COMMITTED,
                CommandExecutionPostgresTest.get(pending).status());
        assertEquals(RecoveryObservation.COMPLETED, engine().observe(claim));
        evidence.add(
                "RR observation before physical resolution=NOT_COMPLETED; after commit=COMPLETED;"
                        + " commitBetweenReads="
                        + commitBetweenReads);
    }

    @Test
    void l2m_e01_e_command_noop() {
        noEffect(false);
    }

    @Test
    void l2m_e01_e_command_reject() {
        noEffect(true);
    }

    void noEffect(boolean rejected) {
        var c = f.command(rejected ? 3 : 2, new PotCommand.UpdatePotDetails(new Label("P")));
        var input = L1CommandStore.claim(c.requestId(), f.admit(c));
        long before = f.jdbc.queryForObject("select count(*) from v3_pot_event", Long.class);
        var attempt = engine().execute(input);
        assertEquals(ConsumptionEngine.Status.COMMITTED, attempt.status());
        assertEquals(
                rejected ? "REJECTED" : "UNCHANGED",
                f.jdbc.queryForObject(
                        "select outcome from v3_command_result where request_id=?",
                        String.class,
                        c.requestId()));
        assertEquals(
                before, f.jdbc.queryForObject("select count(*) from v3_pot_event", Long.class));
        assertEquals(
                2L,
                f.jdbc.queryForObject(
                        "select current_version from v3_pot_identity where pot_id=?",
                        Long.class,
                        f.p.value()));
        assertEquals(RecoveryObservation.COMPLETED, engine().observe(input));
    }

    @Test
    void l2m_e02_g_commit_ack() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
    }

    @Test
    void l2m_e02_g_rollback_ack() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.BEFORE_COMMIT);
    }

    @Test
    void l2m_r9_10() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
    }

    void wire(CommitAcknowledgementProxy.Fault fault) throws Exception {
        var before = vector();
        try (var proxy =
                new CommitAcknowledgementProxy(POSTGRES.getHost(), POSTGRES.getMappedPort(5432))) {
            var source =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://127.0.0.1:"
                                    + proxy.localPort()
                                    + "/"
                                    + POSTGRES.getDatabaseName()
                                    + "?currentSchema="
                                    + f.schema
                                    + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                            POSTGRES.getUsername(),
                            POSTGRES.getPassword());
            var factory = f.entityManagerFactory(source);
            try {
                var manager =
                        new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
                manager.setDataSource(source);
                manager.setNestedTransactionAllowed(false);
                var owner = new SpringAttemptTransactions(source, manager);
                var physicalFailures = new ArrayList<AttemptTransactions.Failure>();
                var armed =
                        new ForwardingTransactions(owner) {
                            public <T> T write(Supplier<T> action) {
                                proxy.armed.set(fault);
                                try {
                                    return owner.write(action);
                                } catch (AttemptTransactions.Failure failure) {
                                    physicalFailures.add(failure);
                                    throw failure;
                                }
                            }
                        };
                var business =
                        new ExecutePotCommand(
                                new JdbcPotCommandStore(source, owner),
                                new JdbcPotCommandObservation(source),
                                f.new FixtureAuthority(source),
                                new JdbcPotHistoryAdapter(source),
                                new JdbcPotRevisionAdapter(source),
                                Clock.systemUTC());
                var attempt =
                        engine(business, new JdbcConsumptionSlotStore(source), armed)
                                .execute(claim);
                evidence.add("wire=" + fault + " attempt=" + attempt);
                assertEquals(1, proxy.intercepted.get());
                assertEquals(1, physicalFailures.size());
                assertEquals(
                        AttemptTransactions.Resolution.UNKNOWN,
                        physicalFailures.getFirst().resolution());
                assertNotNull(attempt.cause());
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, attempt.resolution());
                if (fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT) {
                    assertEquals(1, proxy.committedReplies.get());
                    assertEquals(ConsumptionEngine.Status.COMMITTED, attempt.status());
                    assertEquals(RecoveryObservation.COMPLETED, attempt.observation());
                    assertEquals(
                            0L,
                            f.jdbc.queryForObject(
                                    "select count(*) from consumption_slot where consumable_id=?",
                                    Long.class,
                                    claim.consumableId()));
                    assertEquals(
                            3L,
                            f.jdbc.queryForObject(
                                    "select current_version from v3_pot_identity where pot_id=?",
                                    Long.class,
                                    f.p.value()));
                } else {
                    assertEquals(ConsumptionEngine.Status.RECOVERY_PENDING, attempt.status());
                    assertEquals(RecoveryObservation.NOT_COMPLETED, attempt.observation());
                    assertEquals(before, vector());
                }
            } finally {
                factory.destroy();
            }
        }
    }

    @Test
    void completionFromEarlierCallbackPoisonsCurrentAttemptAndWitnessSlotSurvives()
            throws Exception {
        var retained = new AtomicReference<SlotCompletion>();
        var before = vector();
        var original = wrapped(p -> p.finalizeIn(() -> retained.set(currentCompletion.get())));
        unchanged(before, engine(original, slots, f.tx).execute(claim));
        var next =
                wrapped(
                        p ->
                                p.finalizeIn(
                                        () -> {
                                            assertThrows(
                                                    RuntimeException.class,
                                                    retained.get()::complete);
                                            currentCompletion.get().complete();
                                        }));
        unchanged(before, engine(next, slots, f.tx).execute(claim));
    }

    @Test
    void completionDeleteZeroAndOtherConsumptionKeyUntouched() throws Exception {
        var witness = UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe");
        f.jdbc.update(
                "insert into consumption_routing_contract values"
                        + " (?,'IDENTITY_REQUEST','sha256-v1',4)",
                witness);
        f.jdbc.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int,'QUARANTINED' from (select"
                    + " consumption_hash_v1('IDENTITY_REQUEST',?::uuid) h) x",
                witness, claim.consumableId(), claim.consumableId());
        var before = vector();
        var business =
                wrapped(
                        p ->
                                p.finalizeIn(
                                        () -> {
                                            assertThrows(
                                                    RuntimeException.class,
                                                    () ->
                                                            slots.deleteProtected(
                                                                    new ClaimedSlot(
                                                                            claim.consumptionId(),
                                                                            claim.consumableId(),
                                                                            new UUID(99, 99))));
                                            currentCompletion.get().complete();
                                        }));
        unchanged(before, engine(business, slots, f.tx).execute(claim));
    }

    @Test
    void successAtNMinusOneDoesNotAddFailureOrRunCession() {
        f.jdbc.update(
                "update consumption_slot set attempt_count=2 where consumable_id=?",
                claim.consumableId());
        committed();
    }

    @Test
    void l2m_l1_c_c01() throws Exception {
        f.c01DurableMutationsNoopRejectionAndExactHistory();
    }

    @Test
    void l2m_l1_c_c02() throws Exception {
        f.c02SameComponentCollisionReacquiresAndPreservesObservation();
    }

    @Test
    void l2m_l1_c_c03() throws Exception {
        f.c03DistinctComponentsAndFinancialWeightCollisionRemainCompatible();
    }

    @Test
    void l2m_l1_c_c04() throws Exception {
        f.c04DeletionVersusExpenseCreationAndFinancialPayerParticipantBothOrders();
    }

    @Test
    void l2m_l1_c_c05() throws Exception {
        f.c05ExpenseDeletionReleasesReferenceAndPreservesHistoricalFinancials();
    }

    @Test
    void l2m_l1_c_c06() throws Exception {
        f.c06AttachmentRevocationNoopAndRejectionAllRevalidate();
    }

    @Test
    void l2m_l1_c_c07() throws Exception {
        f.c07IdentityRevocationAndNegativeAuthorityFactsStayProtected();
    }

    @Test
    void l2m_l1_c_c08() throws Exception {
        f.c08NoopObsoleteAfterConcurrentChangeAndMutationAgainstPotDeletion();
    }

    @Test
    void l2m_l1_c_c09() throws Exception {
        f.c09SameRequestFinalizesOnceAndEquivalentRequestsOwnTheirResults();
    }

    @Test
    void l2m_l1_c_c10() throws Exception {
        f.c10FaultsBeforeAfterEveryWriterRollbackWholeCommandUnit();
    }

    @Test
    void l2m_l1_c_c11() throws Exception {
        f.c11DeferredCommitFailureRollsBackAndDoesNotBecomeBusinessRejection();
    }

    @Test
    void l2m_l1_c_c13() throws Exception {
        f.c13RealLostCommitAcknowledgementAndRollbackBeforeCommitRecoverWithoutGuessing();
    }

    @Test
    void l2m_l1_c_c14() throws Exception {
        f.c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes();
    }

    @Test
    void l2m_l1_c_c15() throws Exception {
        f.c15ClaimProtectedThroughCommitAfterDeadlineAndTakeoverWaits();
    }

    @Test
    void l2m_l1_c_c16() throws Exception {
        f.c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition();
    }

    @Test
    void l2m_l1_c_c17() throws Exception {
        f.c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders();
    }

    @Test
    void l2m_l1_c_c18() throws Exception {
        f.c18CompositionRejectsMismatchedManagersAmbientAndPostSuffixWriters();
    }

    @Test
    void l2m_l1_c_c19() throws Exception {
        f.c19DurableIntentionCodecExactRationalsIntegrityAndNegativeExistence();
    }

    @Test
    void l2m_l1_c_c20() throws Exception {
        f.c20IndependentNestedEffectsAreRejectedAndOuterUnitPoisoned();
    }

    @Test
    void l2m_l1_c_c21() throws Exception {
        f.c21NMinusOneFailuresThenSuccessAndExpiryConsumesNoBudget();
    }

    @Test
    void l2m_l1_c_c22() throws Exception {
        f.c22NoopAndRejectionHoldIdentityAndPotUntilPhysicalResolution();
    }

    @Test
    void l2m_l1_c_c24() throws Exception {
        f.c24PreparationFailureAndInconsistentUsageCannotCommitNegativeFacts();
    }

    @Test
    void l2m_l1_f_f02() throws Exception {
        f.f02InvalidBindingCompletesWhilePotIsLockedAndIdentityStillFenced();
        f.captureEvidence();
        f.setup();
        f.f02InvalidBindingHoldsIdentityUntilCommit();
    }

    @Test
    void l2m_l1_f_f03() throws Exception {
        f.f03DurableInvalidListsAreTechnicalAndValidImpossibleTargetsAreBusinessRejections();
        f.captureEvidence();
        f.setup();
        f.f03InvalidAdmissionConstructorsLeaveNoDurableCommandOrSlot();
    }

    @Test
    void l2m_l1_f_f05() throws Exception {
        f.f05StaticBusinessConstraintSetExactlyMatchesCatalogAndLeavesPublicationDeferred();
        f.captureEvidence();
        f.setup();
        f.f05BusinessForeignKeyViolationStillFailsBeforePublicationAndRollsBack();
    }

    @FunctionalInterface
    interface Scenario {
        void run() throws Exception;
    }

    void historical(Scenario scenario) throws Exception {
        f.captureEvidence();
        f.setup();
        scenario.run();
        evidence.add(
                "Historical scenario rerun through canonical Engine, cession stimuli test-only;"
                        + " timeline="
                        + f.timeline);
    }

    @Test
    void l2m_r1_08() throws Exception {
        historical(f::c15ClaimProtectedThroughCommitAfterDeadlineAndTakeoverWaits);
    }

    @Test
    void l2m_c04_a() throws Exception {
        historical(f::c09SameRequestFinalizesOnceAndEquivalentRequestsOwnTheirResults);
    }

    @Test
    void l2m_c04_c() throws Exception {
        historical(f::c15ClaimProtectedThroughCommitAfterDeadlineAndTakeoverWaits);
    }

    @Test
    void l2m_c04_d() throws Exception {
        boundary("after-delete");
    }

    @Test
    void l2m_c04_e() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
    }

    @Test
    void l2m_c04_f() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.BEFORE_COMMIT);
    }

    @Test
    void l2m_c04_h() {
        committed();
        assertEquals(
                0L, f.jdbc.queryForObject("select count(*) from partition_ownership", Long.class));
    }

    @Test
    void l2m_c04_i() throws Exception {
        singleXid();
    }

    @Test
    void l2m_c04_i_status() throws Exception {
        sqlCut("v3_command", null);
    }

    @Test
    void l2m_c04_i_result() throws Exception {
        sqlCut("v3_command_result", null);
    }

    @Test
    void l2m_c04_l() throws Exception {
        completionDeleteZeroAndOtherConsumptionKeyUntouched();
    }

    @Test
    void l2m_c06_f() throws Exception {
        historical(f::c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders);
    }

    @Test
    void l2m_c06_g() throws Exception {
        historical(f::c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders);
    }

    @Test
    void l2m_c06_i() throws Exception {
        requiresNew(false);
    }

    @Test
    void l2m_c06_l() throws Exception {
        wire(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
    }

    @Test
    void l2m_c06_m() {
        l2m_r1_10();
        committed();
    }

    @Test
    void l2m_e01_a() {
        singleXid();
    }

    @Test
    void l2m_e01_b() throws Exception {
        historical(f::c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition);
    }

    @Test
    void l2m_e01_c() throws Exception {
        historical(f::c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition);
    }

    @Test
    void l2m_e01_f() throws Exception {
        publicationCut("publish", false);
    }

    @Test
    void l2m_e01_g_pot_comp() throws Exception {
        historical(f::c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition);
    }

    @Test
    void l2m_e01_h() throws Exception {
        historical(f::c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition);
    }

    @Test
    void l2m_e02_a_create() throws Exception {
        historical(f::c01DurableMutationsNoopRejectionAndExactHistory);
    }

    @Test
    void l2m_e02_a_update() {
        singleXid();
    }

    @Test
    void l2m_e02_a_multitarget() throws Exception {
        historical(f::c01DurableMutationsNoopRejectionAndExactHistory);
    }

    @Test
    void l2m_e02_b() {
        noEffect(false);
    }

    @Test
    void l2m_e02_c() {
        noEffect(true);
    }

    @Test
    void l2m_e02_h() throws Exception {
        historical(f::c09SameRequestFinalizesOnceAndEquivalentRequestsOwnTheirResults);
    }

    @Test
    void l2m_e02_f_01() throws Exception {
        beforeFirstEffectCut();
    }

    @Test
    void l2m_e02_f_02() throws Exception {
        publicationCut("finishTerminal", true);
    }

    @Test
    void l2m_e02_f_03() throws Exception {
        sqlCut("v3_pot_event", null);
    }

    @Test
    void l2m_e02_f_06() throws Exception {
        publicationCut("publish", false);
    }

    @Test
    void rollbackAfterEachDenseCounterAndPositionAndAtFlush() throws Exception {
        for (String table : List.of("v3_event_counter", "v3_event_position"))
            for (String family : List.of("POT", "COMMAND_COMPLETION")) {
                sqlCut(table, family);
                f.jdbc.execute("drop trigger fixture_i2_cut on " + table);
                f.jdbc.execute("drop function fixture_i2_cut_fn()");
            }
        var before = vector();
        var factory = f.entityManagerFactory(f.source);
        try {
            var manager =
                    new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            manager.setDataSource(f.source);
            manager.setNestedTransactionAllowed(false);
            var owner = new SpringAttemptTransactions(f.source, manager);
            var store = new JdbcPotCommandStore(f.source, owner);
            var commands =
                    (PotCommandStore)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    PotCommandStore.class.getClassLoader(),
                                    new Class<?>[] {PotCommandStore.class},
                                    (proxy, method, args) -> {
                                        try {
                                            if (method.getName().equals("publish"))
                                                SpringAttemptTransactions.guarded(
                                                        f.source,
                                                        () -> {
                                                            var em =
                                                                    org.springframework.orm.jpa
                                                                            .EntityManagerFactoryUtils
                                                                            .getTransactionalEntityManager(
                                                                                    factory
                                                                                            .getObject());
                                                            em.persist(
                                                                    new com.kartaguez.pocoma.infra
                                                                            .persistence.primary.jpa
                                                                            .v3commandfixture
                                                                            .CommandProbe(
                                                                            678, null));
                                                            return null;
                                                        });
                                            return method.invoke(store, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException
                                                        failure) {
                                            throw failure.getCause();
                                        }
                                    });
            var business =
                    new ExecutePotCommand(
                            commands,
                            new JdbcPotCommandObservation(f.source),
                            f.authority,
                            f.history,
                            f.revisions,
                            Clock.systemUTC());
            unchanged(before, engine(business, slots, owner).execute(claim));
        } finally {
            factory.destroy();
        }
    }

    void publicationCut(String methodName, boolean beforeCall) throws Exception {
        var before = vector();
        var real = new JdbcPotCommandStore(f.source, f.tx);
        var commands =
                (PotCommandStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                PotCommandStore.class.getClassLoader(),
                                new Class<?>[] {PotCommandStore.class},
                                (proxy, method, args) -> {
                                    try {
                                        if (beforeCall && method.getName().equals(methodName))
                                            throw new IllegalStateException("before " + methodName);
                                        var result = method.invoke(real, args);
                                        if (!beforeCall && method.getName().equals(methodName))
                                            throw new IllegalStateException("after " + methodName);
                                        return result;
                                    } catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
        var business =
                new ExecutePotCommand(
                        commands,
                        new JdbcPotCommandObservation(f.source),
                        f.authority,
                        f.history,
                        f.revisions,
                        Clock.systemUTC());
        unchanged(before, engine(business, slots, f.tx).execute(claim));
    }

    void sqlCut(String table, String family) throws Exception {
        var before = vector();
        f.jdbc.execute(
                "create function fixture_i2_cut_fn() returns trigger language plpgsql as $$ begin "
                        + (family == null ? "" : "if new.family='" + family + "' then ")
                        + "raise exception 'I2 cut after "
                        + table
                        + " "
                        + family
                        + "' using errcode='23514';"
                        + (family == null ? "" : "end if; ")
                        + "return new; end $$");
        f.jdbc.execute(
                "create trigger fixture_i2_cut after insert or update on "
                        + table
                        + " for each row execute function fixture_i2_cut_fn()");
        unchanged(before, engine().execute(claim));
    }

    @Test
    void l2m_e02_i_abs() throws Exception {
        invalidEvent("absent");
    }

    @Test
    void l2m_e02_i_unknown() throws Exception {
        invalidEvent("unknown");
    }

    @Test
    void l2m_e02_i_wrong() throws Exception {
        invalidEvent("wrong");
    }

    void invalidEvent(String variant) throws Exception {
        var other =
                f.command(2, new PotCommand.UpdatePotDetails(new Label("other pending intention")));
        if (variant.equals("wrong")) f.admit(other);
        var before = vector();
        var business =
                wrapped(
                        p -> {
                            // Invalid publication at the real mutation event boundary, before any
                            // dense counter.
                            var c = currentCompletion.get();
                            p.finalizeIn(
                                    () -> {
                                        c.complete();
                                        SpringAttemptTransactions.guarded(
                                                f.source,
                                                () -> {
                                                    f.jdbc.update(
                                                            "insert into"
                                                                + " v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at)"
                                                                + " values"
                                                                + " (?,?,3,?,clock_timestamp())",
                                                            new UUID(879, 1),
                                                            f.p.value(),
                                                            variant.equals("absent")
                                                                    ? null
                                                                    : variant.equals("unknown")
                                                                            ? new UUID(879, 2)
                                                                            : other.requestId());
                                                    if (variant.equals("wrong"))
                                                        f.jdbc.execute(
                                                                "set constraints"
                                                                    + " v3_pot_event_request"
                                                                    + " immediate");
                                                    return null;
                                                });
                                    });
                        });
        var attempt = engine(business, slots, f.tx).execute(claim);
        unchanged(before, attempt);
        var cause = attempt.cause();
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        assertEquals(
                variant.equals("absent") ? "23502" : variant.equals("unknown") ? "23503" : "23514",
                ((SQLException) cause).getSQLState());
    }

    @Test
    void l2m_l1_f_f01() throws Exception {
        var fixture = new PotHistoryPostgresTest();
        fixture.freshSchema();
        fixture.f01PointQueriesUseIndexesAndBoundedBuffersAcrossHistoryDepth();
        fixture = new PotHistoryPostgresTest();
        fixture.freshSchema();
        fixture.f01MaintenanceAuditRetainsEveryGlobalChainCheck();
    }

    void revisionProof(int number) throws Exception {
        var fixture = new WriteRevisionPostgresTest();
        fixture.setup();
        switch (number) {
            case 202 -> fixture.f202RejectsLocalCorruptionAndRollsBackCandidate();
            case 203 -> fixture.f203UnrelatedOldGapDoesNotBlockWriteButAuditRejects();
            case 204 -> fixture.f204FinancialReferenceAndIdentityCorruptionBlockWrite();
            case 205 -> fixture.f205GroupedBatchesAndNotLoadedRemainSelective();
            case 206 -> fixture.f206SameRevisionCannotCloseTwiceUnderActualLockWait();
            case 207 -> fixture.f207EffectIndexAvoidsScanningOldRevisions();
            default -> throw new AssertionError(number);
        }
        evidence.add(
                "Exact unchanged F2 primitive oracle rerun="
                        + number
                        + "; revision guard now common; autonomous B use retained");
    }

    @Test
    void l2m_l1_f2_f201() throws Exception {
        new WriteRevisionPerformancePostgresTest().f201WriteDepthProfiles();
    }

    @Test
    void l2m_l1_f2_f202() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_f203() throws Exception {
        revisionProof(203);
    }

    @Test
    void l2m_l1_f2_f204() throws Exception {
        revisionProof(204);
    }

    @Test
    void l2m_l1_f2_f205() throws Exception {
        revisionProof(205);
    }

    @Test
    void l2m_l1_f2_f206() throws Exception {
        revisionProof(206);
    }

    @Test
    void l2m_l1_f2_f207() throws Exception {
        revisionProof(207);
    }

    @Test
    void l2m_l1_f2_continuite_globale() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_continuite_locale() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_premiere_revision_naissance() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_from_to_valide() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_absence_de_chevauchement() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_au_plus_une_ouverte() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_presence_d_une_ouverte() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_identites_references() throws Exception {
        revisionProof(204);
    }

    @Test
    void l2m_l1_f2_aucune_v_sans_effet() throws Exception {
        revisionProof(202);
    }

    @Test
    void l2m_l1_f2_completude_composantes() throws Exception {
        revisionProof(205);
    }

    @Test
    void l2m_l1_f2_fermeture_insertion_atomiques() throws Exception {
        revisionProof(206);
    }

    @Test
    void l2m_l1_f2_aucun_effet_partiel() throws Exception {
        revisionProof(204);
    }

    @Test
    void l2m_l1_f2_mutations_concurrentes() throws Exception {
        revisionProof(206);
    }

    @Test
    void l2m_l1_f2_claim_identity_pot() throws Exception {
        f.c22NoopAndRejectionHoldIdentityAndPotUntilPhysicalResolution();
    }

    @Test
    void l2m_l1_f2_publication_atomique_evenements_resultats() {
        f.c01DurableMutationsNoopRejectionAndExactHistory();
    }

    @Test
    void engineRejectsLocallyCorruptRevisionWithoutCessionOrPartialCommit() throws Exception {
        for (String defect :
                List.of("missing-open", "missing-first", "future-open", "missing-predecessor")) {
            if (!defect.equals("missing-open")) {
                f.captureEvidence();
                f.setup();
                f.base();
            }
            var c =
                    f.command(
                            2,
                            new PotCommand.UpdatePotDetails(new Label("must rollback " + defect)));
            var input = L1CommandStore.claim(c.requestId(), f.admit(c));
            // Controlled corruption of a disposable test schema only. Restore all trigger behavior
            // at fixture commit; the Engine always executes with ordinary constraints enabled.
            var fixtureManager = new DataSourceTransactionManager(f.source);
            new TransactionTemplate(fixtureManager)
                    .execute(
                            status -> {
                                f.jdbc.execute("set local session_replication_role=replica");
                                switch (defect) {
                                    case "missing-open" ->
                                            f.jdbc.update(
                                                    "update v3_pot_details set active_to=2 where"
                                                            + " pot_id=?",
                                                    f.p.value());
                                    case "missing-first" ->
                                            f.jdbc.update(
                                                    "update v3_pot_details set local_version=2"
                                                            + " where pot_id=?",
                                                    f.p.value());
                                    case "future-open" ->
                                            f.jdbc.update(
                                                    "update v3_pot_details set active_from=99 where"
                                                            + " pot_id=?",
                                                    f.p.value());
                                    case "missing-predecessor" ->
                                            f.jdbc.update(
                                                    "update v3_pot_details set local_version=3"
                                                            + " where pot_id=?",
                                                    f.p.value());
                                }
                                return null;
                            });
            var before = vector();
            var business =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(f.source, f.tx),
                            new JdbcPotCommandObservation(f.source),
                            f.authority,
                            f.history,
                            f.revisions,
                            Clock.systemUTC());
            unchanged(
                    before,
                    engine(business, new JdbcConsumptionSlotStore(f.source), f.tx).execute(input));
        }
    }

    @Test
    void preparationCommitCleanupFailureNeverAnnouncesBusinessCommit() throws Exception {
        var before = vector();
        var reads = new AtomicInteger();
        var owner =
                new ForwardingTransactions(f.tx) {
                    public <T> T read(Function<RecoveryReadContext, T> action) {
                        return f.tx.read(
                                context -> {
                                    if (reads.incrementAndGet() == 1)
                                        TransactionSynchronizationManager.registerSynchronization(
                                                new TransactionSynchronization() {
                                                    public void afterCommit() {
                                                        throw new IllegalStateException(
                                                                "preparation cleanup failure");
                                                    }
                                                });
                                    return action.apply(context);
                                });
                    }
                };
        var result = engine(participant, slots, owner).execute(claim);
        assertEquals(ConsumptionEngine.Status.RECOVERY_PENDING, result.status());
        assertEquals(RecoveryObservation.NOT_COMPLETED, result.observation());
        assertEquals(before, vector());
    }

    void beforeFirstEffectCut() throws Exception {
        var before = vector();
        var writer =
                (com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotRevisionPort)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {
                                    com.kartaguez.pocoma.engine.write.pot.port.persistence.v3
                                            .PotRevisionPort.class
                                },
                                (proxy, method, args) -> {
                                    throw new IllegalStateException("before first business write");
                                });
        var business =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(f.source, f.tx),
                        new JdbcPotCommandObservation(f.source),
                        f.authority,
                        f.history,
                        writer,
                        Clock.systemUTC());
        unchanged(before, engine(business, slots, f.tx).execute(claim));
    }

    @Test
    void l2m_r8_01() throws Exception {
        completionDeleteZeroAndOtherConsumptionKeyUntouched();
    }

    @Test
    void l2m_r8_02() throws Exception {
        singleXid();
    }

    @Test
    void l2m_c03_e() throws Exception {
        historical(f::c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes);
    }

    @Test
    void l2m_c03_f() throws Exception {
        historical(f::c15ClaimProtectedThroughCommitAfterDeadlineAndTakeoverWaits);
    }

    @Test
    void l2m_c03_g() throws Exception {
        historical(f::c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes);
    }

    @Test
    void l2m_c03_h() throws Exception {
        historical(f::c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes);
    }

    @Test
    void l2m_l1_c_summary_c01() {
        f.c02SameComponentCollisionReacquiresAndPreservesObservation();
    }

    @Test
    void l2m_l1_c_summary_c02() {
        f.f02InvalidBindingCompletesWhilePotIsLockedAndIdentityStillFenced();
    }

    @Test
    void l2m_l1_c_summary_c03() {
        f.c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders();
    }

    @Test
    void l2m_l1_c_summary_c04() {
        f.c01DurableMutationsNoopRejectionAndExactHistory();
    }

    @Test
    void l2m_l1_f_summary_f01() {
        f.c01DurableMutationsNoopRejectionAndExactHistory();
    }

    @Test
    void l2m_l1_f_summary_f02() throws Exception {
        l2m_l1_f_f01();
    }

    @Test
    void l2m_l1_f2_summary_f201() throws Exception {
        revisionProof(205);
        revisionProof(206);
    }

    @Test
    void l2m_l1_f2_summary_f202() throws Exception {
        new WriteRevisionPerformancePostgresTest().f201WriteDepthProfiles();
    }
}
