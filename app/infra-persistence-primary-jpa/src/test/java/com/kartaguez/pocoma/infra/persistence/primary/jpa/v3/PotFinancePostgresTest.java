package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.CalculatePotBalances;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigInteger;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Real C execution fixture reused by composition; identity/admission/acquisition remain named
 * fixtures.
 */
@Testcontainers
class PotFinancePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = CommandExecutionPostgresTest.POSTGRES;
    final CommandExecutionPostgresTest f = new CommandExecutionPostgresTest();

    @BeforeEach
    void setup() {
        f.setup();
    }

    @AfterEach
    void collect() throws Exception {
        f.collect();
    }

    ExpenseFinancials financial(long amount, ShareholderId payer, Optional<Weight> override) {
        return new ExpenseFinancials(
                f.p,
                f.e,
                new Amount(new Fraction(amount, 1)),
                payer,
                Map.of(f.b, Optional.empty(), f.c, override));
    }

    PotBalanceCalculator.Balances balance(long version) {
        return f.tx.snapshot(
                () ->
                        new CalculatePotBalances(f.history, new PotBalanceCalculator())
                                .atVersion(f.p, version)
                                .orElseThrow());
    }

    void exact(long v, String a, String b, String c) throws Exception {
        var actual = balance(v);
        var expected = Map.of(f.a, a, f.b, b, f.c, c);
        assertEquals(expected.keySet(), actual.values().keySet());
        expected.forEach(
                (id, value) ->
                        assertEquals(
                                value,
                                actual.values().get(id).numerator()
                                        + "/"
                                        + actual.values().get(id).denominator()));
        assertEquals(
                Fraction.ZERO,
                actual.values().values().stream().reduce(Fraction.ZERO, Fraction::add));
        Path out = Path.of("target/v3-pot-evidence/D-finance-" + f.schema + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(
                out,
                "V=" + v + " expected=" + expected + " actual=" + actual + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    void weight(long observed, ShareholderId id, long n, long d) {
        assertEquals(
                Outcome.CHANGED,
                f.run(
                                f.command(
                                        observed,
                                        new UpdateShareholderWeights(
                                                List.of(
                                                        new ShareholderTransitions.WeightIntent(
                                                                id,
                                                                new Weight(new Fraction(n, d)))))))
                        .outcome());
    }

    @Test
    void d10HistoricalDefaultSpecificAndFinancialChangesThroughC() throws Exception {
        f.base();
        weight(2, f.b, 1, 2);
        var original = financial(100, f.a, Optional.of(new Weight(new Fraction(3, 2))));
        f.run(f.command(3, f.expense(original)));
        exact(4, "100/1", "-25/1", "-75/1");
        var old = f.snapshot();
        weight(4, f.b, 3, 2);
        exact(5, "100/1", "-50/1", "-50/1");
        assertEquals(old.expenses(), f.snapshot().expenses());
        f.run(
                f.command(
                        5,
                        new UpdateExpenseFinancials(
                                financial(100, f.a, Optional.of(new Weight(new Fraction(1, 2)))))));
        exact(6, "100/1", "-75/1", "-25/1");
        f.run(f.command(6, new UpdateExpenseFinancials(financial(28, f.b, Optional.empty()))));
        exact(7, "0/1", "56/5", "-56/5");
        f.run(f.command(7, new UpdateExpenseDetails(f.e, new Label("new"), f.date.plusDays(1))));
        exact(8, "0/1", "56/5", "-56/5");
        f.run(f.command(8, new DeleteExpense(f.e)));
        exact(9, "0/1", "0/1", "0/1");
        f.run(f.command(9, new DeleteShareholder(f.c)));
        exact(10, "0/1", "0/1", "0/1");
        f.run(f.command(10, new DeletePot()));
        assertTrue(
                f.tx.snapshot(
                                () ->
                                        new CalculatePotBalances(
                                                        f.history, new PotBalanceCalculator())
                                                .atVersion(f.p, 11))
                        .isEmpty());
        for (long v : new long[] {6, 4, 8, 5, 7, 4}) {
            if (v == 4) exact(v, "100/1", "-25/1", "-75/1");
            if (v == 5) exact(v, "100/1", "-50/1", "-50/1");
            if (v == 6) exact(v, "100/1", "-75/1", "-25/1");
            if (v == 7 || v == 8) exact(v, "0/1", "56/5", "-56/5");
        }
        assertEquals(old, f.tx.snapshot(() -> f.history.atVersion(f.p, 4).orElseThrow()));
        f.dense();
    }

    @Test
    void d11AttachmentsAllSpecificWeightsAndArbitraryRationals() throws Exception {
        f.base();
        var huge = BigInteger.TEN.pow(35).add(BigInteger.valueOf(39));
        var financials =
                new ExpenseFinancials(
                        f.p,
                        f.e,
                        new Amount(new Fraction(huge, BigInteger.valueOf(7))),
                        f.a,
                        Map.of(
                                f.b,
                                Optional.of(new Weight(Fraction.decimal("0.125"))),
                                f.c,
                                Optional.of(new Weight(new Fraction(3, 8)))));
        f.run(f.command(2, f.expense(financials)));
        var before = balance(3);
        assertEquals(new Fraction(huge, BigInteger.valueOf(7)), before.values().get(f.a));
        assertEquals(new Fraction(huge.negate(), BigInteger.valueOf(28)), before.values().get(f.b));
        assertEquals(
                new Fraction(huge.multiply(BigInteger.valueOf(-3)), BigInteger.valueOf(28)),
                before.values().get(f.c));
        weight(3, f.b, 123, 7);
        assertEquals(before.values(), balance(4).values());
        f.run(
                f.command(
                        4,
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                f.a, new Name("A"), Optional.of(f.creator))))));
        assertEquals(before.values(), balance(5).values());
        f.run(
                f.command(
                        5,
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                f.b, new Name("B"), Optional.empty())))));
        assertEquals(before.values(), balance(6).values());
        var rejected =
                f.run(
                        f.command(
                                f.memberEui,
                                "member-token",
                                6,
                                new UpdateShareholderDetails(
                                        List.of(
                                                new ShareholderTransitions.DetailsIntent(
                                                        f.b,
                                                        new Name("B"),
                                                        Optional.of(f.member))))));
        // Detached member no longer has mutation rights: no invitation is simulated.
        f.rejected(rejected, "FORBIDDEN");
        assertEquals(6, f.storeVersion());
        assertEquals(before.values(), balance(6).values());
        assertEquals(
                financials,
                f.tx.snapshot(
                        () ->
                                f.history
                                        .financialAtVersion(f.p, 3)
                                        .orElseThrow()
                                        .expenses()
                                        .get(f.e)
                                        .financials()
                                        .value()));
    }

    @Test
    void d12FinancialWeightCollisionRecalculatesFromFreshFacts() throws Exception {
        f.base();
        f.run(f.command(2, f.expense(financial(100, f.a, Optional.empty()))));
        var cmd = f.command(3, new UpdateExpenseFinancials(financial(120, f.a, Optional.empty())));
        var token = f.admit(cmd);
        var paused = f.new Paused();
        var work =
                f.threads.submit(
                        () ->
                                f.engine(
                                                paused,
                                                f.store,
                                                f.revisions,
                                                new RetryPolicy(3, Duration.ZERO))
                                        .execute(cmd.requestId(), token));
        CommandExecutionPostgresTest.await(paused.prepared);
        weight(3, f.b, 3, 1);
        exact(4, "100/1", "-75/1", "-25/1");
        paused.resume.countDown();
        assertEquals(Cause.COLLISION, CommandExecutionPostgresTest.get(work).cause());
        f.pending(cmd.requestId(), 1);
        assertEquals(
                5L,
                f.engine()
                        .execute(cmd.requestId(), f.acquire(cmd.requestId()))
                        .result()
                        .orElseThrow()
                        .resultingPotVersion());
        exact(5, "120/1", "-90/1", "-30/1");
        exact(3, "100/1", "-50/1", "-50/1");
        assertEquals(
                3L,
                f.jdbc.queryForObject(
                        "select observed_version from v3_command where request_id=?",
                        Long.class,
                        cmd.requestId()));
        f.dense();
    }

    @Test
    void d13GroupedSelectiveFinanceReadsAndCorruptionFailClosed() throws Exception {
        f.base();
        f.run(f.command(2, f.expense(financial(100, f.a, Optional.empty()))));
        var recording = new PotHistoryPostgresTest.RecordingDataSource(f.source);
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                recording));
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var reader = new JdbcPotHistoryAdapter(recording);
        tx.execute(status -> reader.financialAtVersion(f.p, 3));
        var count = recording.sql.size();
        assertTrue(
                recording.sql.stream()
                        .noneMatch(
                                sql ->
                                        sql.contains("pot_details")
                                                || sql.contains("shareholder_details")
                                                || sql.contains("expense_details")));
        for (int i = 2; i <= 21; i++) {
            var e = new ExpenseId(new UUID(33, i));
            var financial =
                    new ExpenseFinancials(
                            f.p,
                            e,
                            new Amount(Fraction.ONE),
                            f.a,
                            Map.of(f.b, Optional.empty(), f.c, Optional.empty()));
            f.run(
                    f.command(
                            f.storeVersion(),
                            new CreateExpense(
                                    new ExpenseDetails(f.p, e, new Label("E"), f.date),
                                    financial)));
        }
        recording.sql.clear();
        tx.execute(status -> reader.financialAtVersion(f.p, 23));
        assertEquals(count, recording.sql.size());
        Files.createDirectories(Path.of("target/v3-pot-evidence"));
        Files.writeString(
                Path.of("target/v3-pot-evidence/D-queries-" + f.schema + ".txt"),
                "count one and 21 Expenses=" + count + "\n" + recording.sql);
        f.jdbc.update(
                "delete from v3_expense_participant where financial_revision_id in(select"
                        + " revision_id from v3_expense_financials where object_id=?)",
                f.e.value());
        var failure =
                assertThrows(
                        com.kartaguez.pocoma.engine.write.pot.port.execution.v3
                                .PotCommandTransactions.Failure.class,
                        () -> balance(3));
        assertInstanceOf(InconsistentPotFactsException.class, failure.getCause());
        assertThrows(
                InconsistentPotFactsException.class, () -> f.history.financialAtVersion(f.p, 3));
    }

    @Test
    void d14FinancialRollbackAtEveryPublicationBoundary() throws Exception {
        for (String phase :
                List.of("recordVersion", "reviseExpenseFinancials", "finish", "publish")) {
            if (f.storeVersion() != 0) {
                f.captureEvidence();
                f.setup();
            }
            f.base();
            f.run(f.command(2, f.expense(financial(100, f.a, Optional.empty()))));
            var before = f.snapshot();
            var numbers = balance(3);
            var cmd =
                    f.command(
                            3,
                            new UpdateExpenseFinancials(financial(120, f.b, Optional.of(f.two))));
            var token = f.admit(cmd);
            var writer =
                    f.intercept(
                            PotRevisionPort.class,
                            f.revisions,
                            CommandExecutionPostgresTest.NOTHING,
                            (method, args) -> {
                                if (method.getName().equals(phase))
                                    throw new IllegalStateException("financial cut");
                            });
            var commands =
                    f.intercept(
                            com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore
                                    .class,
                            f.store,
                            CommandExecutionPostgresTest.NOTHING,
                            (method, args) -> {
                                if (method.getName().equals(phase))
                                    throw new IllegalStateException("publication cut");
                            });
            assertEquals(
                    Status.RETRY_SCHEDULED,
                    f.engine(f.tx, commands, writer, new RetryPolicy(3, Duration.ZERO))
                            .execute(cmd.requestId(), token)
                            .status());
            f.pending(cmd.requestId(), 1);
            assertEquals(before, f.snapshot());
            assertEquals(numbers, balance(3));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "select count(*) from v3_pot_event where request_id=?",
                            Integer.class,
                            cmd.requestId()));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "select count(*) from v3_command_completion_event where subject_id=?",
                            Integer.class,
                            cmd.requestId()));
            assertEquals(
                    4L,
                    f.engine()
                            .execute(cmd.requestId(), f.acquire(cmd.requestId()))
                            .result()
                            .orElseThrow()
                            .resultingPotVersion());
            exact(4, "0/1", "80/1", "-80/1");
            exact(3, "100/1", "-50/1", "-50/1");
            f.dense();
        }
    }

    @Test
    void d15OneVersionComposesWeightAndFinancialPrimitives() throws Exception {
        f.base();
        f.run(f.command(2, f.expense(financial(100, f.a, Optional.empty()))));
        var before = f.snapshot();
        var view =
                f.tx.snapshot(
                        () ->
                                f.history
                                        .current(
                                                f.p,
                                                f.creator,
                                                new PotHistoryPort.Selection(
                                                        Set.of(f.a, f.b, f.c),
                                                        Set.of(f.e),
                                                        false,
                                                        false,
                                                        true,
                                                        false,
                                                        true))
                                        .orElseThrow());
        var references = new HashMap<ShareholderId, ShareholderReference>();
        var weights = new HashMap<ShareholderId, Versioned<ShareholderWeight>>();
        view.shareholders()
                .forEach(
                        (id, s) -> {
                            references.put(id, s.reference());
                            weights.put(
                                    id,
                                    ((ComponentRead.Present<ShareholderWeight>) s.weight())
                                            .revision());
                        });
        var weight =
                changed(
                        ShareholderTransitions.updateWeights(
                                view.facts(),
                                3,
                                List.of(
                                        new ShareholderTransitions.WeightIntent(
                                                f.b, new Weight(new Fraction(3, 1)))),
                                references,
                                weights));
        var expense = view.expenses().get(f.e);
        var financial =
                changed(
                        ExpenseTransitions.updateFinancials(
                                view.facts(),
                                3,
                                ((ComponentRead.Present<ExpenseHeader>) expense.header())
                                        .revision(),
                                ((ComponentRead.Present<ExpenseFinancials>) expense.financials())
                                        .revision(),
                                financial(120, f.a, Optional.empty()),
                                references));
        // Fixture-only composition of B primitives: no new public combined Command is invented.
        f.tx.write(
                () -> {
                    f.revisions.recordVersion(f.p, 3, 4, Instant.now());
                    f.revisions.reviseShareholderWeights(f.p, weight);
                    f.revisions.reviseExpenseFinancials(f.p, financial);
                    return null;
                });
        exact(4, "120/1", "-90/1", "-30/1");
        exact(3, "100/1", "-50/1", "-50/1");
        assertEquals(4, f.storeVersion());
        assertEquals(2, f.snapshot().shareholders().get(f.b).weight().localVersion());
        assertEquals(2, f.snapshot().expenses().get(f.e).financials().localVersion());
        assertEquals(before, f.tx.snapshot(() -> f.history.atVersion(f.p, 3).orElseThrow()));
    }

    @SuppressWarnings("unchecked")
    private <T> TransitionResult.Changed<T> changed(TransitionResult<T> result) {
        return (TransitionResult.Changed<T>)
                assertInstanceOf(TransitionResult.Changed.class, result);
    }
}
