package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3commandfixture.CommandProbe;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.sql.DataSource;

/**
 * Internal Command proof. Authority and initial PENDING/claim acquisition below are explicit
 * fixtures.
 */
@Testcontainers
class CommandExecutionPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    final PotId p = new PotId(new UUID(11, 1));
    final ShareholderId a = new ShareholderId(new UUID(12, 1)),
            b = new ShareholderId(new UUID(12, 2)),
            c = new ShareholderId(new UUID(12, 3));
    final ExpenseId e = new ExpenseId(new UUID(13, 1));
    final UserId creator = new UserId(new UUID(14, 1)), member = new UserId(new UUID(14, 2));
    final Eui eui = new Eui("fixture-issuer", "creator"),
            memberEui = new Eui("fixture-issuer", "member");
    final Weight one = new Weight(Fraction.ONE), two = new Weight(new Fraction(2, 1));
    final LocalDate date = LocalDate.of(2026, 10, 25);
    DataSource source;
    JdbcTemplate jdbc;
    PotCommandJdbcTransactions tx;
    JdbcPotCommandStore store;
    JdbcPotHistoryAdapter history;
    JdbcPotRevisionAdapter revisions;
    FixtureAuthority authority;
    String schema;
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> timeline = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        schema = "v3_c_" + UUID.randomUUID().toString().replace("-", "");
        source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + "&socketTimeout=20",
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .locations(
                        "classpath:db/v3-pot/migration", "classpath:db/v3-pot-execution/migration")
                .load()
                .migrate();
        jdbc.execute(
                "create table fixture_actor(issuer text,subject text,revision bigint not"
                    + " null,user_id uuid,token text,primary key(issuer,subject))");
        jdbc.update(
                "insert into fixture_actor values (?,?,1,?,?)",
                eui.issuer(),
                eui.subject(),
                creator.value(),
                "creator-token");
        jdbc.update(
                "insert into fixture_actor values (?,?,1,?,?)",
                memberEui.issuer(),
                memberEui.subject(),
                member.value(),
                "member-token");
        jdbc.execute("create table v3_command_probe(id bigint primary key,value text not null)");
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        tx = new PotCommandJdbcTransactions(source, manager);
        store = new JdbcPotCommandStore(source, tx);
        history = new JdbcPotHistoryAdapter(source);
        revisions = new JdbcPotRevisionAdapter(source);
        authority = new FixtureAuthority(source);
    }

    @AfterEach
    void collect() throws Exception {
        threads.shutdownNow();
        assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
        captureEvidence();
    }

    void captureEvidence() throws Exception {
        var output =
                Path.of(
                        "target/v3-pot-evidence",
                        getClass().getSimpleName() + "-" + schema + ".txt");
        Files.createDirectories(output.getParent());
        var evidence =
                new StringBuilder(
                        "Internal fixture authority and initial claim; no Identity/Discovery"
                            + " certification\n");
        evidence.append("PostgreSQL: ")
                .append(jdbc.queryForObject("select version()", String.class))
                .append('\n');
        for (var table :
                List.of(
                        "v3_pot_identity",
                        "v3_pot_version",
                        "v3_pot_header",
                        "v3_pot_details",
                        "v3_shareholder_header",
                        "v3_shareholder_details",
                        "v3_shareholder_weight",
                        "v3_expense_header",
                        "v3_expense_details",
                        "v3_expense_financials",
                        "v3_expense_participant",
                        "v3_command_result",
                        "v3_pot_event",
                        "v3_pot_event_component",
                        "v3_command_completion_event",
                        "v3_event_counter",
                        "v3_event_position"))
            evidence.append(table)
                    .append(": ")
                    .append(jdbc.queryForList("select * from " + table))
                    .append('\n');
        evidence.append("requests: ")
                .append(
                        jdbc.queryForList(
                                "select"
                                    + " request_id,requester_issuer,requester_subject,observed_version,operation,status"
                                    + " from v3_command"))
                .append('\n');
        evidence.append("slots: ")
                .append(
                        jdbc.queryForList(
                                "select request_id,claim_token is not null as"
                                    + " claimed,claimed_until,retry_after,attempt_count,state from"
                                    + " v3_command_slot"))
                .append('\n');
        evidence.append("barriers: ").append(timeline).append('\n');
        Files.writeString(output, evidence);
    }

    ExecutePotCommand engine() {
        return engine(tx, store, revisions, new RetryPolicy(3, Duration.ZERO));
    }

    ExecutePotCommand engine(
            PotCommandTransactions transactions,
            PotCommandStore commands,
            PotRevisionPort writer,
            RetryPolicy policy) {
        return new ExecutePotCommand(
                transactions, commands, authority, history, writer, policy, Clock.systemUTC());
    }

    PotCommand command(long observed, Intent intent) {
        return command(eui, "creator-token", observed, intent);
    }

    PotCommand command(Eui who, String token, long observed, Intent intent) {
        return new PotCommand(UUID.randomUUID(), who, token, p, observed, intent);
    }

    UUID admit(PotCommand command) {
        jdbc.update(
                "insert into"
                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                    + " values (?,?,?,?,?,?,?,?::jsonb)",
                command.requestId(),
                command.requester().issuer(),
                command.requester().subject(),
                command.bindingToken(),
                command.potId().value(),
                command.observedVersion(),
                command.intent().getClass().getSimpleName(),
                PotCommandJson.encode(command.intent()));
        jdbc.update("insert into v3_command_slot(request_id) values (?)", command.requestId());
        return acquire(command.requestId());
    }

    UUID acquire(UUID request) {
        var token = UUID.randomUUID();
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            jdbc.queryForList(
                                    "select request_id from v3_command_slot where request_id=? for"
                                        + " update",
                                    request);
                            return jdbc.update(
                                                    "update v3_command_slot set"
                                                        + " claim_token=?,claimed_until=clock_timestamp()+interval"
                                                        + " '5 minutes' where request_id=? and"
                                                        + " state='AVAILABLE' and (claim_token is"
                                                        + " null or"
                                                        + " claimed_until<=clock_timestamp()) and"
                                                        + " (retry_after is null or"
                                                        + " retry_after<=clock_timestamp())",
                                                    token,
                                                    request)
                                            == 1
                                    ? token
                                    : null;
                        });
    }

    Result run(PotCommand command) {
        var attempt = engine().execute(command.requestId(), admit(command));
        assertEquals(Status.COMPLETED, attempt.status(), attempt.toString());
        return attempt.result().orElseThrow();
    }

    void base() {
        assertEquals(1L, run(command(0, new CreatePot(new Label("P")))).resultingPotVersion());
        assertEquals(
                2L,
                run(command(
                                1,
                                new AddShareholders(
                                        List.of(
                                                new ShareholderTransitions.Creation(
                                                        a, new Name("A"), Optional.empty(), one),
                                                new ShareholderTransitions.Creation(
                                                        b, new Name("B"), Optional.empty(), one),
                                                new ShareholderTransitions.Creation(
                                                        c, new Name("C"), Optional.empty(), one)))))
                        .resultingPotVersion());
        // Initial business fixture: attachment predating this campaign, not an invitation
        // implementation.
        jdbc.update(
                "update v3_shareholder_details set user_id=? where pot_id=? and object_id=?",
                member.value(),
                p.value(),
                b.value());
    }

    ExpenseFinancials financial(ShareholderId payer, ShareholderId participant) {
        return new ExpenseFinancials(
                p,
                e,
                new Amount(new Fraction(100, 1)),
                payer,
                Map.of(participant, Optional.empty()));
    }

    CreateExpense expense(ExpenseFinancials f) {
        return new CreateExpense(new ExpenseDetails(p, e, new Label("E"), date), f);
    }

    PotHistoryPort.Snapshot snapshot() {
        return tx.snapshot(() -> history.atVersion(p, store.currentVersion(p)).orElseThrow());
    }

    void rejected(Result result, String reason) {
        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(reason, result.rejection());
    }

    void pending(UUID request, int failures) {
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "select status from v3_command where request_id=?", String.class, request));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from v3_command_result where request_id=?",
                        Integer.class,
                        request));
        assertEquals(
                failures,
                jdbc.queryForObject(
                        "select attempt_count from v3_command_slot where request_id=?",
                        Integer.class,
                        request));
    }

    void dense() {
        for (String family : List.of("POT", "COMMAND_COMPLETION")) {
            var positions =
                    jdbc.queryForList(
                            "select position from v3_event_position where family=? order by"
                                + " position",
                            Long.class,
                            family);
            for (int i = 0; i < positions.size(); i++) assertEquals(i + 1L, positions.get(i));
            assertEquals(
                    (long) positions.size(),
                    jdbc.queryForObject(
                            "select position from v3_event_counter where family=?",
                            Long.class,
                            family));
        }
    }

    void safeReferences() {
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from v3_expense_header e join v3_expense_financials f on"
                            + " f.pot_id=e.pot_id and f.object_id=e.object_id and f.active_to is"
                            + " null join v3_shareholder_header s on s.pot_id=e.pot_id and"
                            + " s.active_to is null where e.active_to is null and not e.deleted and"
                            + " s.deleted and (f.payer_id=s.object_id or exists(select 1 from"
                            + " v3_expense_participant x where"
                            + " x.financial_revision_id=f.revision_id and"
                            + " x.shareholder_id=s.object_id))",
                        Integer.class));
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    static <T> T get(Future<T> future) {
        try {
            return future.get(25, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    final class Paused implements PotCommandTransactions {
        final CountDownLatch prepared = new CountDownLatch(1), resume = new CountDownLatch(1);

        @Override
        public <T> T snapshot(Supplier<T> action) {
            var value = tx.snapshot(action);
            timeline.add("preparation committed");
            prepared.countDown();
            await(resume);
            return value;
        }

        @Override
        public <T> T write(Supplier<T> action) {
            return tx.write(action);
        }
    }

    final class FixtureAuthority implements PotActorAuthority {
        final JdbcTemplate sql;

        FixtureAuthority(DataSource source) {
            sql = new JdbcTemplate(source);
        }

        @Override
        public Witness observe(PotCommand command) {
            var rows =
                    sql.queryForList(
                            "select revision,user_id,token from fixture_actor where issuer=? and"
                                + " subject=?",
                            command.requester().issuer(),
                            command.requester().subject());
            if (rows.isEmpty()) return new Witness(0, Optional.empty());
            var row = rows.getFirst();
            return new Witness(
                    ((Number) row.get("revision")).longValue(),
                    Objects.equals(command.bindingToken(), row.get("token"))
                                    && row.get("user_id") != null
                            ? Optional.of(new UserId((UUID) row.get("user_id")))
                            : Optional.empty());
        }

        @Override
        public boolean protect(PotCommand command, Witness observed) {
            sql.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<Void>)
                            connection -> {
                                try (var lock =
                                        connection.prepareStatement(
                                                "select pg_advisory_xact_lock(hashtextextended(?,"
                                                    + " 732))")) {
                                    lock.setString(
                                            1,
                                            command.requester().issuer()
                                                    + "\n"
                                                    + command.requester().subject());
                                    lock.execute();
                                    return null;
                                }
                            });
            sql.queryForList(
                    "select revision from fixture_actor where issuer=? and subject=? for update",
                    command.requester().issuer(),
                    command.requester().subject());
            return observe(command).equals(observed);
        }
    }

    @Test
    void c01DurableMutationsNoopRejectionAndExactHistory() {
        base();
        var old = tx.snapshot(() -> history.atVersion(p, 2).orElseThrow());
        assertEquals(
                Outcome.UNCHANGED, run(command(2, new UpdatePotDetails(new Label("P")))).outcome());
        assertEquals(3L, run(command(2, expense(financial(a, b)))).resultingPotVersion());
        assertEquals(
                4L,
                run(command(3, new UpdateExpenseDetails(e, new Label("renamed"), date.plusDays(1))))
                        .resultingPotVersion());
        rejected(run(command(3, new DeleteExpense(e))), "OBSOLETE");
        assertEquals(
                5L,
                run(command(
                                4,
                                new UpdateExpenseFinancials(
                                        new ExpenseFinancials(
                                                p,
                                                e,
                                                new Amount(new Fraction(200, 1)),
                                                a,
                                                Map.of(b, Optional.of(two))))))
                        .resultingPotVersion());
        assertEquals(
                6L,
                run(command(
                                4,
                                new UpdateShareholderWeights(
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(a, two),
                                                new ShareholderTransitions.WeightIntent(b, two)))))
                        .resultingPotVersion());
        assertEquals(
                7L,
                run(command(
                                6,
                                new UpdateShareholderDetails(
                                        List.of(
                                                new ShareholderTransitions.DetailsIntent(
                                                        a, new Name("AA"), Optional.empty()),
                                                new ShareholderTransitions.DetailsIntent(
                                                        b, new Name("BB"), Optional.of(member))))))
                        .resultingPotVersion());
        rejected(
                run(
                        command(
                                5,
                                new UpdateShareholderWeights(
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(a, one),
                                                new ShareholderTransitions.WeightIntent(b, one))))),
                "OBSOLETE");
        rejected(run(command(memberEui, "member-token", 7, new DeleteShareholder(c))), "FORBIDDEN");
        rejected(run(command(7, new DeleteShareholder(a))), "REFERENCED");
        assertEquals(8L, run(command(7, new DeleteExpense(e))).resultingPotVersion());
        assertEquals(9L, run(command(8, new DeleteShareholder(a))).resultingPotVersion());
        assertEquals(10L, run(command(9, new DeletePot())).resultingPotVersion());
        rejected(run(command(10, new UpdatePotDetails(new Label("late")))), "DELETED");
        assertEquals(old, tx.snapshot(() -> history.atVersion(p, 2).orElseThrow()));
        var finalState = snapshot();
        assertTrue(finalState.pot().header().value().deleted());
        assertEquals(2, finalState.expenses().get(e).header().localVersion());
        assertEquals(2, finalState.expenses().get(e).details().localVersion());
        assertEquals(2, finalState.expenses().get(e).financials().localVersion());
        assertEquals(
                new Fraction(200, 1),
                finalState.expenses().get(e).financials().value().amount().value());
        assertEquals(2, finalState.shareholders().get(a).header().localVersion());
        assertEquals(2, finalState.shareholders().get(a).details().localVersion());
        assertEquals(2, finalState.shareholders().get(a).weight().localVersion());
        assertEquals(10, storeVersion());
        safeReferences();
        dense();
    }

    long storeVersion() {
        return tx.snapshot(() -> store.currentVersion(p));
    }

    @Test
    void c02SameComponentCollisionReacquiresAndPreservesObservation() {
        base();
        var loser = command(2, new UpdatePotDetails(new Label("second")));
        var token = admit(loser);
        var paused = new Paused();
        var future =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(loser.requestId(), token));
        await(paused.prepared);
        assertEquals(
                3L,
                run(command(2, new UpdatePotDetails(new Label("first")))).resultingPotVersion());
        paused.resume.countDown();
        var collision = get(future);
        assertEquals(Status.RETRY_SCHEDULED, collision.status());
        assertEquals(Cause.COLLISION, collision.cause());
        pending(loser.requestId(), 1);
        var newToken = acquire(loser.requestId());
        assertNotNull(newToken);
        assertNotEquals(token, newToken);
        var result = engine().execute(loser.requestId(), newToken);
        rejected(result.result().orElseThrow(), "OBSOLETE");
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "select observed_version from v3_command where request_id=?",
                        Long.class,
                        loser.requestId()));
        assertEquals("first", snapshot().pot().details().value().label().value());
        assertEquals(3, storeVersion());
        dense();
    }

    @Test
    void c03DistinctComponentsAndFinancialWeightCollisionRemainCompatible() {
        base();
        var details =
                command(
                        2,
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                a, new Name("changed"), Optional.empty()))));
        var token = admit(details);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(details.requestId(), token));
        await(paused.prepared);
        assertEquals(
                3L,
                run(command(
                                2,
                                new UpdateShareholderWeights(
                                        List.of(new ShareholderTransitions.WeightIntent(a, two)))))
                        .resultingPotVersion());
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        assertEquals(
                4L,
                engine().execute(details.requestId(), acquire(details.requestId()))
                        .result()
                        .orElseThrow()
                        .resultingPotVersion());
        run(command(4, expense(financial(a, b))));
        var financials =
                command(
                        5,
                        new UpdateExpenseFinancials(
                                new ExpenseFinancials(
                                        p,
                                        e,
                                        new Amount(new Fraction(300, 1)),
                                        a,
                                        Map.of(b, Optional.empty()))));
        var ft = admit(financials);
        var fp = new Paused();
        var fw =
                threads.submit(
                        () ->
                                engine(fp, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(financials.requestId(), ft));
        await(fp.prepared);
        run(
                command(
                        5,
                        new UpdateShareholderWeights(
                                List.of(new ShareholderTransitions.WeightIntent(b, two)))));
        fp.resume.countDown();
        assertEquals(Cause.COLLISION, get(fw).cause());
        assertEquals(
                7L,
                engine().execute(financials.requestId(), acquire(financials.requestId()))
                        .result()
                        .orElseThrow()
                        .resultingPotVersion());
        var state = snapshot();
        assertEquals(two, state.shareholders().get(b).weight().value().defaultWeight());
        assertEquals(
                new Fraction(300, 1),
                state.expenses().get(e).financials().value().amount().value());
        dense();
    }

    @Test
    void c04DeletionVersusExpenseCreationAndFinancialPayerParticipantBothOrders() {
        // 2 creation/modification x 2 payer/participant x 2 commit orders, fresh Pot identity per
        // variant.
        for (boolean modification : List.of(false, true))
            for (boolean payer : List.of(false, true))
                for (boolean deletionFirst : List.of(false, true)) {
                    if (storeVersion() != 0) {
                        try {
                            captureEvidence();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        setup();
                    }
                    base();
                    if (modification) run(command(2, expense(financial(b, b))));
                    long observed = storeVersion();
                    var target = financial(payer ? a : b, payer ? b : a);
                    var removal = command(observed, new DeleteShareholder(a));
                    var expenseCommand =
                            command(
                                    observed,
                                    modification
                                            ? new UpdateExpenseFinancials(target)
                                            : expense(target));
                    var delayed = deletionFirst ? expenseCommand : removal;
                    var winner = deletionFirst ? removal : expenseCommand;
                    var token = admit(delayed);
                    var paused = new Paused();
                    var work =
                            threads.submit(
                                    () ->
                                            engine(
                                                            paused,
                                                            store,
                                                            revisions,
                                                            new RetryPolicy(3, Duration.ZERO))
                                                    .execute(delayed.requestId(), token));
                    await(paused.prepared);
                    assertEquals(Outcome.CHANGED, run(winner).outcome());
                    paused.resume.countDown();
                    assertEquals(Cause.COLLISION, get(work).cause());
                    pending(delayed.requestId(), 1);
                    var result =
                            engine().execute(delayed.requestId(), acquire(delayed.requestId()));
                    assertEquals(Status.COMPLETED, result.status());
                    if (deletionFirst)
                        assertEquals(Outcome.REJECTED, result.result().orElseThrow().outcome());
                    else rejected(result.result().orElseThrow(), "REFERENCED");
                    var state = snapshot();
                    assertEquals(
                            deletionFirst, state.shareholders().get(a).header().value().deleted());
                    assertEquals(observed + 1, storeVersion());
                    safeReferences();
                    dense();
                    timeline.add(
                            "D02-S modification="
                                    + modification
                                    + " payer="
                                    + payer
                                    + " deletionFirst="
                                    + deletionFirst
                                    + " PASS");
                }
    }

    @Test
    void c05ExpenseDeletionReleasesReferenceAndPreservesHistoricalFinancials() {
        base();
        run(command(2, expense(financial(a, b))));
        var old = snapshot();
        var removal = command(3, new DeleteShareholder(a));
        var token = admit(removal);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(removal.requestId(), token));
        await(paused.prepared);
        run(command(3, new DeleteExpense(e)));
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        assertEquals(
                Outcome.CHANGED,
                engine().execute(removal.requestId(), acquire(removal.requestId()))
                        .result()
                        .orElseThrow()
                        .outcome());
        assertEquals(old.expenses().get(e).financials(), snapshot().expenses().get(e).financials());
        assertEquals(old, tx.snapshot(() -> history.atVersion(p, 3).orElseThrow()));
        safeReferences();
        dense();
    }

    @Test
    void c06AttachmentRevocationNoopAndRejectionAllRevalidate() {
        base();
        var noop = command(memberEui, "member-token", 2, new UpdatePotDetails(new Label("P")));
        var token = admit(noop);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(noop.requestId(), token));
        await(paused.prepared);
        run(
                command(
                        2,
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                b, new Name("B"), Optional.empty())))));
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        rejected(
                engine().execute(noop.requestId(), acquire(noop.requestId()))
                        .result()
                        .orElseThrow(),
                "FORBIDDEN");
        // A prepared rejection loses validity when the member acquires an active attachment.
        var denied = command(memberEui, "member-token", 3, new UpdatePotDetails(new Label("P")));
        var dt = admit(denied);
        var dp = new Paused();
        var dw =
                threads.submit(
                        () ->
                                engine(dp, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(denied.requestId(), dt));
        await(dp.prepared);
        jdbc.update(
                "update fixture_actor set revision=revision+1,user_id=? where issuer=? and"
                    + " subject=?",
                creator.value(),
                memberEui.issuer(),
                memberEui.subject());
        dp.resume.countDown();
        assertEquals(Cause.COLLISION, get(dw).cause());
        assertEquals(
                Outcome.UNCHANGED,
                engine().execute(denied.requestId(), acquire(denied.requestId()))
                        .result()
                        .orElseThrow()
                        .outcome());
        assertEquals(3, storeVersion());
        dense();
    }

    @Test
    void c07IdentityRevocationAndNegativeAuthorityFactsStayProtected() {
        base();
        var command = command(2, new UpdatePotDetails(new Label("new")));
        var token = admit(command);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(command.requestId(), token));
        await(paused.prepared);
        jdbc.update(
                "update fixture_actor set revision=revision+1,user_id=null,token=null where"
                    + " issuer=? and subject=?",
                eui.issuer(),
                eui.subject());
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        rejected(
                engine().execute(command.requestId(), acquire(command.requestId()))
                        .result()
                        .orElseThrow(),
                "INVALID_BINDING");
        assertEquals(2, storeVersion());
        var negative = command(2, new UpdatePotDetails(new Label("P")));
        var nt = admit(negative);
        var np = new Paused();
        var nw =
                threads.submit(
                        () ->
                                engine(np, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(negative.requestId(), nt));
        await(np.prepared);
        jdbc.update(
                "update fixture_actor set revision=revision+1,user_id=?,token=? where issuer=? and"
                    + " subject=?",
                creator.value(),
                "creator-token",
                eui.issuer(),
                eui.subject());
        np.resume.countDown();
        assertEquals(Cause.COLLISION, get(nw).cause());
        assertEquals(
                Outcome.UNCHANGED,
                engine().execute(negative.requestId(), acquire(negative.requestId()))
                        .result()
                        .orElseThrow()
                        .outcome());
        rejected(
                run(command(eui, "member-token", 2, new UpdatePotDetails(new Label("P")))),
                "INVALID_BINDING");
        dense();
    }

    @Test
    void c08NoopObsoleteAfterConcurrentChangeAndMutationAgainstPotDeletion() {
        base();
        var noop = command(2, new UpdatePotDetails(new Label("P")));
        var token = admit(noop);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(noop.requestId(), token));
        await(paused.prepared);
        run(command(2, new UpdatePotDetails(new Label("changed"))));
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        rejected(
                engine().execute(noop.requestId(), acquire(noop.requestId()))
                        .result()
                        .orElseThrow(),
                "OBSOLETE");
        var delete = command(3, new DeletePot());
        var dt = admit(delete);
        var dp = new Paused();
        var dw =
                threads.submit(
                        () ->
                                engine(dp, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(delete.requestId(), dt));
        await(dp.prepared);
        run(
                command(
                        3,
                        new UpdateShareholderWeights(
                                List.of(new ShareholderTransitions.WeightIntent(a, two)))));
        dp.resume.countDown();
        assertEquals(Cause.COLLISION, get(dw).cause());
        rejected(
                engine().execute(delete.requestId(), acquire(delete.requestId()))
                        .result()
                        .orElseThrow(),
                "OBSOLETE");
        var mutation =
                command(
                        4,
                        new UpdateShareholderWeights(
                                List.of(new ShareholderTransitions.WeightIntent(b, two))));
        var mt = admit(mutation);
        var mp = new Paused();
        var mw =
                threads.submit(
                        () ->
                                engine(mp, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(mutation.requestId(), mt));
        await(mp.prepared);
        run(command(4, new DeletePot()));
        mp.resume.countDown();
        assertEquals(Cause.COLLISION, get(mw).cause());
        rejected(
                engine().execute(mutation.requestId(), acquire(mutation.requestId()))
                        .result()
                        .orElseThrow(),
                "DELETED");
        assertEquals(one, snapshot().shareholders().get(b).weight().value().defaultWeight());
        dense();
    }

    @Test
    void c09SameRequestFinalizesOnceAndEquivalentRequestsOwnTheirResults() {
        base();
        var first = command(2, new UpdatePotDetails(new Label("new")));
        var token = admit(first);
        var p1 = new Paused();
        var p2 = new Paused();
        var f1 =
                threads.submit(
                        () ->
                                engine(p1, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(first.requestId(), token));
        var f2 =
                threads.submit(
                        () ->
                                engine(p2, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(first.requestId(), token));
        await(p1.prepared);
        await(p2.prepared);
        p1.resume.countDown();
        assertEquals(Status.COMPLETED, get(f1).status());
        p2.resume.countDown();
        assertEquals(Status.CLAIM_LOST, get(f2).status());
        assertEquals(3, storeVersion());
        assertEquals(Status.COMPLETED, engine().execute(first.requestId(), token).status());
        var equivalent = command(2, new UpdatePotDetails(new Label("new")));
        rejected(run(equivalent), "OBSOLETE");
        assertEquals(
                2,
                jdbc.queryForObject(
                        "select count(*) from v3_command_result where request_id in (?,?)",
                        Integer.class,
                        first.requestId(),
                        equivalent.requestId()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from v3_pot_event where request_id=?",
                        Integer.class,
                        first.requestId()));
        dense();
    }

    @SuppressWarnings("unchecked")
    <T> T intercept(
            Class<T> contract,
            T delegate,
            java.util.function.BiConsumer<Method, Object[]> before,
            java.util.function.BiConsumer<Method, Object[]> after) {
        return (T)
                Proxy.newProxyInstance(
                        contract.getClassLoader(),
                        new Class<?>[] {contract},
                        (proxy, method, args) -> {
                            before.accept(method, args);
                            try {
                                var result = method.invoke(delegate, args);
                                after.accept(method, args);
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    static final java.util.function.BiConsumer<Method, Object[]> NOTHING = (method, args) -> {};

    @Test
    void c10FaultsBeforeAfterEveryWriterRollbackWholeCommandUnit() {
        for (String phase :
                List.of(
                        "before-version",
                        "after-version",
                        "after-revision",
                        "after-finish",
                        "after-publish")) {
            if (storeVersion() != 0) {
                try {
                    captureEvidence();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                setup();
            }
            base();
            var before = snapshot();
            var command = command(2, new UpdatePotDetails(new Label("failed")));
            var token = admit(command);
            var writer =
                    intercept(
                            PotRevisionPort.class,
                            revisions,
                            (m, args) -> {
                                if (phase.equals("before-version")
                                        && m.getName().equals("recordVersion"))
                                    throw new IllegalStateException("injected before version");
                            },
                            (m, args) -> {
                                if ((phase.equals("after-version")
                                                && m.getName().equals("recordVersion"))
                                        || (phase.equals("after-revision")
                                                && m.getName().equals("revisePotDetails")))
                                    throw new IllegalStateException("injected after write");
                            });
            var commands =
                    intercept(
                            PotCommandStore.class,
                            store,
                            NOTHING,
                            (m, args) -> {
                                if ((phase.equals("after-finish") && m.getName().equals("finish"))
                                        || (phase.equals("after-publish")
                                                && m.getName().equals("publish")))
                                    throw new IllegalStateException("injected terminal failure");
                            });
            var attempt =
                    engine(tx, commands, writer, new RetryPolicy(3, Duration.ZERO))
                            .execute(command.requestId(), token);
            assertEquals(Status.RETRY_SCHEDULED, attempt.status(), phase);
            assertEquals(Cause.TECHNICAL, attempt.cause());
            pending(command.requestId(), 1);
            assertEquals(before, snapshot());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from v3_pot_event where request_id=?",
                            Integer.class,
                            command.requestId()));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from v3_command_completion_event where subject_id=?",
                            Integer.class,
                            command.requestId()));
            assertEquals(
                    3L,
                    engine().execute(command.requestId(), acquire(command.requestId()))
                            .result()
                            .orElseThrow()
                            .resultingPotVersion());
            dense();
            timeline.add(phase + " rollback and fresh acquisition PASS");
        }
    }

    @Test
    void c11DeferredCommitFailureRollsBackAndDoesNotBecomeBusinessRejection() {
        base();
        var before = snapshot();
        jdbc.execute(
                "create function fixture_commit_failure() returns trigger language plpgsql as $$"
                    + " begin raise exception 'injected deferred commit' using errcode='23514'; end"
                    + " $$");
        jdbc.execute(
                "create constraint trigger fixture_commit_failure after insert on v3_command_result"
                    + " deferrable initially deferred for each row execute function"
                    + " fixture_commit_failure()");
        var command = command(2, new UpdatePotDetails(new Label("failed")));
        var token = admit(command);
        var attempt = engine().execute(command.requestId(), token);
        assertEquals(Status.RETRY_SCHEDULED, attempt.status());
        assertEquals(Cause.TECHNICAL, attempt.cause());
        pending(command.requestId(), 1);
        assertEquals(before, snapshot());
        dense();
        jdbc.execute("drop trigger fixture_commit_failure on v3_command_result");
        assertEquals(
                Status.COMPLETED,
                engine().execute(command.requestId(), acquire(command.requestId())).status());
        dense();
    }

    @Test
    void c12RetryBudgetDelayQuarantineAndConditionalFailureAccounting() {
        base();
        var command = command(2, new UpdatePotDetails(new Label("eventual")));
        var token = admit(command);
        var broken =
                intercept(
                        PotRevisionPort.class,
                        revisions,
                        (m, args) -> {
                            throw new IllegalStateException("persistent failure");
                        },
                        NOTHING);
        var policy = new RetryPolicy(3, Duration.ofMinutes(1));
        var execution = engine(tx, store, broken, policy);
        assertEquals(
                Status.RETRY_SCHEDULED, execution.execute(command.requestId(), token).status());
        pending(command.requestId(), 1);
        assertNull(acquire(command.requestId()));
        UUID stale = token;
        assertEquals(
                FailureRecorded.CLAIM_LOST,
                tx.write(() -> store.recordFailure(command.requestId(), stale, 3, Duration.ZERO)));
        for (int count = 2; count <= 3; count++) {
            jdbc.update(
                    "update v3_command_slot set retry_after=clock_timestamp()-interval '1 second'"
                        + " where request_id=?",
                    command.requestId());
            var next = acquire(command.requestId());
            assertNotEquals(token, next);
            token = next;
            var attempt = execution.execute(command.requestId(), next);
            assertEquals(
                    count == 3 ? Status.QUARANTINED : Status.RETRY_SCHEDULED, attempt.status());
            pending(command.requestId(), count);
        }
        assertNull(acquire(command.requestId()));
        assertEquals(2, storeVersion());
        run(
                command(
                        2,
                        new UpdateShareholderWeights(
                                List.of(new ShareholderTransitions.WeightIntent(a, two)))));
        assertEquals(3, storeVersion()); // same Pot progresses despite earlier request quarantine
        // Explicit fixture admin: same row, atomic expected-state reset; no source/effect changes.
        assertEquals(
                1,
                jdbc.update(
                        "update v3_command_slot set"
                            + " state='AVAILABLE',attempt_count=0,retry_after=null where"
                            + " request_id=? and state='QUARANTINED'",
                        command.requestId()));
        assertEquals(
                0,
                jdbc.update(
                        "update v3_command_slot set state='AVAILABLE',attempt_count=0 where"
                            + " request_id=? and state='QUARANTINED'",
                        command.requestId()));
        assertEquals(
                4L,
                engine().execute(command.requestId(), acquire(command.requestId()))
                        .result()
                        .orElseThrow()
                        .resultingPotVersion());
        dense();
    }

    @Test
    void c13RealLostCommitAcknowledgementAndRollbackBeforeCommitRecoverWithoutGuessing()
            throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT,
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT)) {
            if (storeVersion() != 0) {
                try {
                    captureEvidence();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                setup();
            }
            base();
            var before = snapshot();
            try (var proxy =
                    new CommitAcknowledgementProxy(
                            POSTGRES.getHost(), POSTGRES.getMappedPort(5432))) {
                var proxied =
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:"
                                        + proxy.localPort()
                                        + "/"
                                        + POSTGRES.getDatabaseName()
                                        + "?currentSchema="
                                        + schema
                                        + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var manager = new DataSourceTransactionManager(proxied);
                manager.setNestedTransactionAllowed(false);
                var pt = new PotCommandJdbcTransactions(proxied, manager);
                PotCommandTransactions armed =
                        new PotCommandTransactions() {
                            @Override
                            public <T> T snapshot(Supplier<T> action) {
                                var result = pt.snapshot(action);
                                proxy.armed.set(fault);
                                return result;
                            }

                            @Override
                            public <T> T write(Supplier<T> action) {
                                return pt.write(action);
                            }
                        };
                var command = command(2, new UpdatePotDetails(new Label("wire")));
                var token = admit(command);
                var execution =
                        new ExecutePotCommand(
                                armed,
                                new JdbcPotCommandStore(proxied, pt),
                                new FixtureAuthority(proxied),
                                new JdbcPotHistoryAdapter(proxied),
                                new JdbcPotRevisionAdapter(proxied),
                                new RetryPolicy(3, Duration.ZERO),
                                Clock.systemUTC());
                var attempt = execution.execute(command.requestId(), token);
                assertEquals(1, proxy.intercepted.get());
                if (fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT) {
                    assertEquals(1, proxy.committedReplies.get());
                    assertEquals(Status.COMPLETED, attempt.status());
                    assertEquals(3, storeVersion());
                    assertEquals(
                            Status.COMPLETED,
                            engine().execute(command.requestId(), token).status());
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "select count(*) from v3_pot_event where request_id=?",
                                    Integer.class,
                                    command.requestId()));
                } else {
                    assertEquals(0, proxy.committedReplies.get());
                    assertEquals(Status.RECOVERY_PENDING, attempt.status());
                    pending(command.requestId(), 0);
                    assertEquals(before, snapshot());
                    jdbc.update(
                            "update v3_command_slot set claimed_until=clock_timestamp()-interval '1"
                                + " second' where request_id=?",
                            command.requestId());
                    assertEquals(
                            3L,
                            engine().execute(command.requestId(), acquire(command.requestId()))
                                    .result()
                                    .orElseThrow()
                                    .resultingPotVersion());
                }
                dense();
                timeline.add("wire " + fault + " observed independently PASS");
            }
        }
    }

    @Test
    void c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes() throws Exception {
        base();
        var command = command(2, new UpdatePotDetails(new Label("fresh")));
        var token = admit(command);
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement =
                    blocker.prepareStatement(
                            "update v3_command_slot set claimed_until=clock_timestamp()-interval '1"
                                + " second' where request_id=?")) {
                statement.setObject(1, command.requestId());
                statement.executeUpdate();
            }
            var work = threads.submit(() -> engine().execute(command.requestId(), token));
            blocked("select claim_token,claimed_until,state from v3_command_slot");
            blocker.commit();
            assertEquals(Status.CLAIM_LOST, get(work).status());
            pending(command.requestId(), 0);
            assertEquals(2, storeVersion());
        }
        var next = acquire(command.requestId());
        assertNotEquals(token, next);
        assertEquals(Status.CLAIM_LOST, engine().execute(command.requestId(), token).status());
        assertEquals(
                FailureRecorded.CLAIM_LOST,
                tx.write(() -> store.recordFailure(command.requestId(), token, 3, Duration.ZERO)));
        assertEquals(
                3L,
                engine().execute(command.requestId(), next)
                        .result()
                        .orElseThrow()
                        .resultingPotVersion());
        dense();
    }

    void blocked(String query) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var waiters =
                    jdbc.queryForList(
                            "select pid,wait_event,pg_blocking_pids(pid) as blockers from"
                                + " pg_stat_activity where datname=current_database() and"
                                + " wait_event_type='Lock' and query like ?",
                            "%" + query + "%");
            if (!waiters.isEmpty()) {
                timeline.add("actual PostgreSQL lock wait: " + waiters);
                return;
            }
            Thread.onSpinWait();
        }
        fail("No actual PostgreSQL lock wait observed: " + query);
    }

    @Test
    void c15ClaimProtectedThroughCommitAfterDeadlineAndTakeoverWaits() {
        base();
        var command = command(2, new UpdatePotDetails(new Label("held")));
        var token = admit(command);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var commands =
                intercept(
                        PotCommandStore.class,
                        store,
                        NOTHING,
                        (method, args) -> {
                            if (method.getName().equals("protectClaim")) {
                                // Controlled expiry AFTER successful protection; emulates
                                // wall-clock passage without sleeps.
                                jdbc.update(
                                        "update v3_command_slot set"
                                            + " claimed_until=clock_timestamp()-interval '1 second'"
                                            + " where request_id=?",
                                        command.requestId());
                                locked.countDown();
                                await(release);
                            }
                        });
        var work =
                threads.submit(
                        () ->
                                engine(tx, commands, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(command.requestId(), token));
        await(locked);
        var takeover = threads.submit(() -> acquire(command.requestId()));
        blocked("select request_id from v3_command_slot");
        assertFalse(takeover.isDone());
        release.countDown();
        assertEquals(Status.COMPLETED, get(work).status());
        assertNull(get(takeover));
        assertEquals(3, storeVersion());
        dense();
    }

    @Test
    void c16DenseSequencingWaitsForPhysicalCommitAndRollbackDoesNotConsumePosition() {
        for (boolean rollback : List.of(false, true)) {
            if (storeVersion() != 0) {
                try {
                    captureEvidence();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                setup();
            }
            base();
            var other = new PotId(new UUID(11, 2));
            var createOther =
                    new PotCommand(
                            UUID.randomUUID(),
                            memberEui,
                            "member-token",
                            other,
                            0,
                            new CreatePot(new Label("other")));
            run(createOther);
            var first = command(2, new UpdatePotDetails(new Label("first")));
            var ft = admit(first);
            var second =
                    new PotCommand(
                            UUID.randomUUID(),
                            memberEui,
                            "member-token",
                            other,
                            1,
                            new UpdatePotDetails(new Label("second")));
            var st = admit(second);
            var published = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var commands =
                    intercept(
                            PotCommandStore.class,
                            store,
                            NOTHING,
                            (method, args) -> {
                                if (method.getName().equals("publish")) {
                                    published.countDown();
                                    await(release);
                                    if (rollback)
                                        throw new IllegalStateException("abort sequenced unit");
                                }
                            });
            long prefix =
                    jdbc.queryForObject(
                            "select position from v3_event_counter where family='POT'", Long.class);
            var fw =
                    threads.submit(
                            () ->
                                    engine(
                                                    tx,
                                                    commands,
                                                    revisions,
                                                    new RetryPolicy(3, Duration.ZERO))
                                            .execute(first.requestId(), ft));
            await(published);
            var sw = threads.submit(() -> engine().execute(second.requestId(), st));
            blocked("update v3_event_counter");
            assertEquals(
                    prefix,
                    jdbc.queryForObject(
                            "select position from v3_event_counter where family='POT'",
                            Long.class));
            assertFalse(sw.isDone());
            release.countDown();
            assertEquals(rollback ? Status.RETRY_SCHEDULED : Status.COMPLETED, get(fw).status());
            assertEquals(Status.COMPLETED, get(sw).status());
            assertEquals(
                    prefix + (rollback ? 1 : 2),
                    jdbc.queryForObject(
                            "select position from v3_event_counter where family='POT'",
                            Long.class));
            dense();
        }
    }

    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan(CommandProbe.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        return factory;
    }

    @Test
    void c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders() {
        base();
        var factory = entityManagerFactory(source);
        var emf = factory.getObject();
        try {
            var manager = new JpaTransactionManager(emf);
            manager.setDataSource(source);
            manager.setNestedTransactionAllowed(false);
            var jpaTx = new PotCommandJdbcTransactions(source, manager);
            var commands = new JdbcPotCommandStore(source, jpaTx);
            for (boolean jpaFirst : List.of(false, true)) {
                var before = snapshot();
                assertThrows(
                        PotCommandTransactions.Failure.class,
                        () ->
                                jpaTx.write(
                                        () -> {
                                            var holder =
                                                    (EntityManagerHolder)
                                                            TransactionSynchronizationManager
                                                                    .getResource(emf);
                                            var em = holder.getEntityManager();
                                            var jdbcWitness =
                                                    jdbc.queryForMap(
                                                            "select pg_backend_pid() as"
                                                                + " pid,pg_current_xact_id()::text"
                                                                + " as xid");
                                            Object[] jpaWitness =
                                                    (Object[])
                                                            em.createNativeQuery(
                                                                            "select"
                                                                                + " pg_backend_pid(),pg_current_xact_id()::text")
                                                                    .getSingleResult();
                                            assertEquals(
                                                    ((Number) jdbcWitness.get("pid")).longValue(),
                                                    ((Number) jpaWitness[0]).longValue());
                                            assertEquals(jdbcWitness.get("xid"), jpaWitness[1]);
                                            timeline.add("same physical JPA/JDBC: " + jdbcWitness);
                                            if (jpaFirst) {
                                                em.persist(new CommandProbe(1, "probe"));
                                                em.flush();
                                            }
                                            try {
                                                // Application validation failure, caught without
                                                // SQL abort: B must still poison.
                                                revisions.recordVersion(p, 2, 3, Instant.now());
                                                revisions.recordVersion(
                                                        p, 999, 1000, Instant.now());
                                            } catch (InconsistentPotFactsException caught) {
                                                timeline.add("caught B writer failure");
                                            }
                                            if (!jpaFirst) {
                                                em.persist(new CommandProbe(1, "probe"));
                                                em.flush();
                                            }
                                            return null;
                                        }));
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "select count(*) from v3_command_probe", Integer.class));
                assertEquals(before, snapshot());
            }
            for (boolean jpaFirst : List.of(false, true)) {
                assertThrows(
                        PotCommandTransactions.Failure.class,
                        () ->
                                jpaTx.write(
                                        () -> {
                                            var em =
                                                    ((EntityManagerHolder)
                                                                    TransactionSynchronizationManager
                                                                            .getResource(emf))
                                                            .getEntityManager();
                                            if (jpaFirst) {
                                                em.persist(new CommandProbe(2, "valid"));
                                                em.flush();
                                            }
                                            revisions.recordVersion(p, 2, 3, Instant.now());
                                            try {
                                                em.persist(new CommandProbe(3, null));
                                                em.flush();
                                            } catch (RuntimeException caught) {
                                                timeline.add("caught JPA failure");
                                            }
                                            return null;
                                        }));
                assertEquals(2, storeVersion());
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "select count(*) from v3_command_probe", Integer.class));
            }
            var command = command(2, new UpdatePotDetails(new Label("JPA manager")));
            assertEquals(
                    Status.COMPLETED,
                    engine(jpaTx, commands, revisions, new RetryPolicy(3, Duration.ZERO))
                            .execute(command.requestId(), admit(command))
                            .status());
            assertEquals("JPA manager", snapshot().pot().details().value().label().value());
            dense();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void c18CompositionRejectsMismatchedManagersAmbientAndPostSuffixWriters() {
        base();
        var other =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var wrong = new DataSourceTransactionManager(other);
        wrong.setNestedTransactionAllowed(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PotCommandJdbcTransactions(source, wrong));
        assertThrows(IllegalArgumentException.class, () -> new JdbcPotCommandStore(other, tx));
        assertThrows(PotCommandTransactions.Failure.class, () -> tx.write(() -> tx.write(() -> 1)));
        var command = command(2, new UpdatePotDetails(new Label("closed")));
        var token = admit(command);
        var commands =
                intercept(
                        PotCommandStore.class,
                        store,
                        NOTHING,
                        (method, args) -> {
                            if (method.getName().equals("publish")) {
                                try {
                                    revisions.recordVersion(p, 3, 4, Instant.now());
                                } catch (RuntimeException caught) {
                                    timeline.add("caught post-suffix writer violation");
                                }
                            }
                        });
        assertEquals(
                Status.RETRY_SCHEDULED,
                engine(tx, commands, revisions, new RetryPolicy(3, Duration.ZERO))
                        .execute(command.requestId(), token)
                        .status());
        assertEquals(2, storeVersion());
        pending(command.requestId(), 1);
        dense();
    }

    @Test
    void c19DurableIntentionCodecExactRationalsIntegrityAndNegativeExistence() {
        base();
        var huge =
                new Fraction(
                        new java.math.BigInteger("100000000000000000000000000000000000001"),
                        new java.math.BigInteger("99999999999999999999999999999999999999"));
        var intent =
                new UpdateExpenseFinancials(
                        new ExpenseFinancials(
                                p,
                                e,
                                new Amount(huge),
                                a,
                                Map.of(b, Optional.of(new Weight(Fraction.decimal("0.125"))))));
        assertEquals(
                intent,
                PotCommandJson.decode(
                        intent.getClass().getSimpleName(), PotCommandJson.encode(intent)));
        var command = command(2, new UpdatePotDetails(new Label("immutable")));
        admit(command);
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "update v3_command set observed_version=3 where request_id=?",
                                command.requestId()));
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "insert into"
                                    + " v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at)"
                                    + " values (?,?,2,?,clock_timestamp())",
                                UUID.randomUUID(),
                                p.value(),
                                UUID.randomUUID()));
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "insert into"
                                    + " v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at)"
                                    + " values (?,?,2,null,clock_timestamp())",
                                UUID.randomUUID(),
                                p.value()));
        var absent = new PotId(new UUID(11, 99));
        var missing =
                new PotCommand(
                        UUID.randomUUID(),
                        eui,
                        "creator-token",
                        absent,
                        1,
                        new UpdatePotDetails(new Label("later")));
        var mt = admit(missing);
        var paused = new Paused();
        var work =
                threads.submit(
                        () ->
                                engine(paused, store, revisions, new RetryPolicy(3, Duration.ZERO))
                                        .execute(missing.requestId(), mt));
        await(paused.prepared);
        run(
                new PotCommand(
                        UUID.randomUUID(),
                        eui,
                        "creator-token",
                        absent,
                        0,
                        new CreatePot(new Label("new pot"))));
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, get(work).cause());
        assertEquals(
                Outcome.CHANGED,
                engine().execute(missing.requestId(), acquire(missing.requestId()))
                        .result()
                        .orElseThrow()
                        .outcome());
        dense();
    }

    @Test
    void c20IndependentNestedEffectsAreRejectedAndOuterUnitPoisoned() {
        base();
        var nestedManager = new DataSourceTransactionManager(source);
        nestedManager.setNestedTransactionAllowed(false);
        var nested = new TransactionTemplate(nestedManager);
        nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(
                PotCommandTransactions.Failure.class,
                () ->
                        tx.write(
                                () -> {
                                    try {
                                        nested.execute(
                                                status -> {
                                                    revisions.recordVersion(p, 2, 3, Instant.now());
                                                    return null;
                                                });
                                    } catch (RuntimeException caught) {
                                        timeline.add(
                                                "independent nested writer rejected and caught");
                                    }
                                    return null;
                                }));
        assertEquals(2, storeVersion());
        dense();
    }

    @Test
    void c21NMinusOneFailuresThenSuccessAndExpiryConsumesNoBudget() {
        base();
        var command = command(2, new UpdatePotDetails(new Label("after two failures")));
        var first = admit(command);
        var broken =
                intercept(
                        PotRevisionPort.class,
                        revisions,
                        (method, args) -> {
                            throw new IllegalStateException("failure");
                        },
                        NOTHING);
        var execution = engine(tx, store, broken, new RetryPolicy(3, Duration.ZERO));
        assertEquals(
                Status.RETRY_SCHEDULED, execution.execute(command.requestId(), first).status());
        var second = acquire(command.requestId());
        assertNotEquals(first, second);
        assertEquals(
                Status.RETRY_SCHEDULED, execution.execute(command.requestId(), second).status());
        pending(command.requestId(), 2);
        var third = acquire(command.requestId());
        assertNotEquals(second, third);
        assertEquals(Status.COMPLETED, engine().execute(command.requestId(), third).status());
        assertEquals(3, storeVersion());
        var abandoned =
                command(3, new UpdatePotDetails(new Label("crash before failure accounting")));
        var stale = admit(abandoned);
        jdbc.update(
                "update v3_command_slot set claimed_until=clock_timestamp()-interval '1 second'"
                    + " where request_id=?",
                abandoned.requestId());
        var fresh = acquire(abandoned.requestId());
        assertNotEquals(stale, fresh);
        pending(abandoned.requestId(), 0);
        assertEquals(
                FailureRecorded.CLAIM_LOST,
                tx.write(
                        () -> store.recordFailure(abandoned.requestId(), stale, 3, Duration.ZERO)));
        pending(abandoned.requestId(), 0);
        assertEquals(Status.COMPLETED, engine().execute(abandoned.requestId(), fresh).status());
        dense();
    }

    @Test
    void c22NoopAndRejectionHoldIdentityAndPotUntilPhysicalResolution() {
        base();
        for (boolean rejection : List.of(false, true)) {
            var command =
                    rejection
                            ? command(memberEui, "member-token", 2, new DeleteShareholder(c))
                            : command(2, new UpdatePotDetails(new Label("P")));
            var token = admit(command);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var commands =
                    intercept(
                            PotCommandStore.class,
                            store,
                            NOTHING,
                            (method, args) -> {
                                if (method.getName().equals("protectPot")) {
                                    locked.countDown();
                                    await(release);
                                }
                            });
            var work =
                    threads.submit(
                            () ->
                                    engine(
                                                    tx,
                                                    commands,
                                                    revisions,
                                                    new RetryPolicy(3, Duration.ZERO))
                                            .execute(command.requestId(), token));
            await(locked);
            var revoke =
                    threads.submit(
                            () ->
                                    jdbc.update(
                                            "update fixture_actor set"
                                                + " revision=revision+1,user_id=null,token=null"
                                                + " where issuer=? and subject=?",
                                            command.requester().issuer(),
                                            command.requester().subject()));
            blocked("update fixture_actor");
            assertFalse(revoke.isDone());
            release.countDown();
            var attempt = get(work);
            assertEquals(Status.COMPLETED, attempt.status());
            assertEquals(
                    rejection ? Outcome.REJECTED : Outcome.UNCHANGED,
                    attempt.result().orElseThrow().outcome());
            assertEquals(1, get(revoke));
            assertEquals(2, storeVersion());
            dense();
        }
    }

    @Test
    void c23LostFailureCessionAcknowledgementNeverDoubleCounts() throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT,
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT)) {
            if (storeVersion() != 0) {
                captureEvidence();
                setup();
            }
            base();
            try (var proxy =
                    new CommitAcknowledgementProxy(
                            POSTGRES.getHost(), POSTGRES.getMappedPort(5432))) {
                var proxied =
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:"
                                        + proxy.localPort()
                                        + "/"
                                        + POSTGRES.getDatabaseName()
                                        + "?currentSchema="
                                        + schema
                                        + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var manager = new DataSourceTransactionManager(proxied);
                manager.setNestedTransactionAllowed(false);
                var pt = new PotCommandJdbcTransactions(proxied, manager);
                var durable = new JdbcPotCommandStore(proxied, pt);
                var commands =
                        intercept(
                                PotCommandStore.class,
                                durable,
                                NOTHING,
                                (method, args) -> {
                                    if (method.getName().equals("recordFailure"))
                                        proxy.armed.set(fault);
                                });
                var writer =
                        intercept(
                                PotRevisionPort.class,
                                new JdbcPotRevisionAdapter(proxied),
                                (method, args) -> {
                                    throw new IllegalStateException("confirmed technical failure");
                                },
                                NOTHING);
                var execution =
                        new ExecutePotCommand(
                                pt,
                                commands,
                                new FixtureAuthority(proxied),
                                new JdbcPotHistoryAdapter(proxied),
                                writer,
                                new RetryPolicy(3, Duration.ZERO),
                                Clock.systemUTC());
                var command = command(2, new UpdatePotDetails(new Label("cession")));
                var token = admit(command);
                assertEquals(
                        Status.RECOVERY_PENDING,
                        execution.execute(command.requestId(), token).status());
                assertEquals(1, proxy.intercepted.get());
                pending(
                        command.requestId(),
                        fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0);
                if (fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT) {
                    assertEquals(
                            FailureRecorded.CLAIM_LOST,
                            tx.write(
                                    () ->
                                            store.recordFailure(
                                                    command.requestId(), token, 3, Duration.ZERO)));
                    pending(command.requestId(), 1);
                } else
                    jdbc.update(
                            "update v3_command_slot set claimed_until=clock_timestamp()-interval '1"
                                + " second' where request_id=?",
                            command.requestId());
                assertEquals(
                        Status.COMPLETED,
                        engine().execute(command.requestId(), acquire(command.requestId()))
                                .status());
                dense();
            }
        }
    }

    @Test
    void c24PreparationFailureAndInconsistentUsageCannotCommitNegativeFacts() {
        base();
        var command = command(2, new UpdatePotDetails(new Label("after preparation failure")));
        var token = admit(command);
        var failingHistory =
                intercept(
                        PotHistoryPort.class,
                        history,
                        NOTHING,
                        (method, args) -> {
                            throw new IllegalStateException("loading fault");
                        });
        var execution =
                new ExecutePotCommand(
                        tx,
                        store,
                        authority,
                        failingHistory,
                        revisions,
                        new RetryPolicy(3, Duration.ZERO),
                        Clock.systemUTC());
        var failed = execution.execute(command.requestId(), token);
        assertEquals(Status.RETRY_SCHEDULED, failed.status());
        assertEquals(Cause.TECHNICAL, failed.cause());
        pending(command.requestId(), 1);
        assertEquals(2, storeVersion());
        assertEquals(
                Status.COMPLETED,
                engine().execute(command.requestId(), acquire(command.requestId())).status());
        run(command(3, expense(financial(a, b))));
        var revision =
                jdbc.queryForObject(
                        "select revision_id from v3_expense_financials where pot_id=? and"
                            + " object_id=?",
                        Long.class,
                        p.value(),
                        e.value());
        jdbc.update("delete from v3_expense_participant where financial_revision_id=?", revision);
        var removal = command(4, new DeleteShareholder(a));
        var rt = admit(removal);
        var corrupt = engine().execute(removal.requestId(), rt);
        assertEquals(Status.RETRY_SCHEDULED, corrupt.status());
        assertEquals(Cause.INCONSISTENT_FACTS, corrupt.cause());
        pending(removal.requestId(), 1);
        assertEquals(
                false,
                jdbc.queryForObject(
                        "select deleted from v3_shareholder_header where object_id=? and active_to"
                            + " is null",
                        Boolean.class,
                        a.value()));
        assertEquals(4, storeVersion());
        jdbc.update(
                "insert into v3_expense_participant(pot_id,financial_revision_id,shareholder_id)"
                    + " values (?,?,?)",
                p.value(),
                revision,
                b.value());
        rejected(
                engine().execute(removal.requestId(), acquire(removal.requestId()))
                        .result()
                        .orElseThrow(),
                "REFERENCED");
        safeReferences();
        dense();
    }
}
