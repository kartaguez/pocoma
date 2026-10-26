package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;

/**
 * Joint L1 proof through durable C commands, B history and D exact balances. No READ projection.
 */
@Testcontainers
class L1IntegrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = CommandExecutionPostgresTest.POSTGRES;
    final PotFinancePostgresTest d = new PotFinancePostgresTest();
    final CommandExecutionPostgresTest f = d.f;

    @BeforeEach
    void setup() {
        d.setup();
    }

    @AfterEach
    void collect() throws Exception {
        d.collect();
    }

    @Test
    void e01CanonicalV1ThroughV9AllEightComponentsAndExactBalances() throws Exception {
        f.run(f.command(0, new CreatePot(new Label("P"))));
        f.run(
                f.command(
                        1,
                        new AddShareholders(
                                List.of(
                                        new ShareholderTransitions.Creation(
                                                f.a, new Name("A"), Optional.empty(), f.one),
                                        new ShareholderTransitions.Creation(
                                                f.b,
                                                new Name("B"),
                                                Optional.of(f.creator),
                                                new Weight(new Fraction(3, 1)))))));
        var financials =
                new ExpenseFinancials(
                        f.p,
                        f.e,
                        new Amount(new Fraction(100, 1)),
                        f.a,
                        Map.of(f.a, Optional.empty(), f.b, Optional.empty()));
        f.run(f.command(2, f.expense(financials)));
        var historical = f.tx.read(context -> f.history.atVersion(f.p, 3).orElseThrow());
        var oldBalance = d.balance(3);
        f.run(f.command(3, new UpdateExpenseDetails(f.e, new Label("E2"), f.date.plusDays(1))));
        f.run(
                f.command(
                        4,
                        new UpdateExpenseFinancials(
                                new ExpenseFinancials(
                                        f.p,
                                        f.e,
                                        new Amount(new Fraction(120, 1)),
                                        f.b,
                                        financials.participants()))));
        d.weight(5, f.a, 3, 1);
        f.run(
                f.command(
                        6,
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                f.a, new Name("A2"), Optional.empty()),
                                        new ShareholderTransitions.DetailsIntent(
                                                f.b, new Name("B2"), Optional.of(f.creator))))));
        f.run(f.command(7, new DeleteExpense(f.e)));
        f.run(f.command(8, new DeletePot()));
        var versions = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));
        Collections.shuffle(versions, new Random(731));
        for (long v : versions) {
            var s = f.tx.read(context -> f.history.atVersion(f.p, v).orElseThrow());
            assertEquals(
                    new PotState(
                            new Versioned<>(
                                    v == 9 ? 2 : 1, v == 9 ? 9 : 1, new PotHeader(f.p, v == 9)),
                            new Versioned<>(1, 1, new PotDetails(f.p, new Label("P"), f.creator))),
                    s.pot());
            assertEquals(v < 2 ? Set.of() : Set.of(f.a, f.b), s.shareholders().keySet());
            if (v >= 2) {
                assertEquals(
                        new ShareholderState(
                                new Versioned<>(1, 2, new ShareholderHeader(f.p, f.a, false)),
                                new Versioned<>(
                                        v >= 7 ? 2 : 1,
                                        v >= 7 ? 7 : 2,
                                        new ShareholderDetails(
                                                f.p,
                                                f.a,
                                                new Name(v >= 7 ? "A2" : "A"),
                                                Optional.empty())),
                                new Versioned<>(
                                        v >= 6 ? 2 : 1,
                                        v >= 6 ? 6 : 2,
                                        new ShareholderWeight(
                                                f.p,
                                                f.a,
                                                new Weight(new Fraction(v >= 6 ? 3 : 1, 1))))),
                        s.shareholders().get(f.a));
                assertEquals(
                        new ShareholderState(
                                new Versioned<>(1, 2, new ShareholderHeader(f.p, f.b, false)),
                                new Versioned<>(
                                        v >= 7 ? 2 : 1,
                                        v >= 7 ? 7 : 2,
                                        new ShareholderDetails(
                                                f.p,
                                                f.b,
                                                new Name(v >= 7 ? "B2" : "B"),
                                                Optional.of(f.creator))),
                                new Versioned<>(
                                        1,
                                        2,
                                        new ShareholderWeight(
                                                f.p, f.b, new Weight(new Fraction(3, 1))))),
                        s.shareholders().get(f.b));
            }
            assertEquals(v < 3 ? Set.of() : Set.of(f.e), s.expenses().keySet());
            if (v >= 3)
                assertEquals(
                        new ExpenseState(
                                new Versioned<>(
                                        v >= 8 ? 2 : 1,
                                        v >= 8 ? 8 : 3,
                                        new ExpenseHeader(f.p, f.e, v >= 8)),
                                new Versioned<>(
                                        v >= 4 ? 2 : 1,
                                        v >= 4 ? 4 : 3,
                                        new ExpenseDetails(
                                                f.p,
                                                f.e,
                                                new Label(v >= 4 ? "E2" : "E"),
                                                v >= 4 ? f.date.plusDays(1) : f.date)),
                                new Versioned<>(
                                        v >= 5 ? 2 : 1,
                                        v >= 5 ? 5 : 3,
                                        new ExpenseFinancials(
                                                f.p,
                                                f.e,
                                                new Amount(new Fraction(v >= 5 ? 120 : 100, 1)),
                                                v >= 5 ? f.b : f.a,
                                                financials.participants()))),
                        s.expenses().get(f.e));
            var balance =
                    f.tx.read(
                            context ->
                                    new com.kartaguez.pocoma.engine.write.pot.v3
                                                    .CalculatePotBalances(
                                                    f.history, new PotBalanceCalculator())
                                            .atVersion(f.p, v));
            if (v == 9) assertTrue(balance.isEmpty());
            else {
                var expected =
                        v == 1
                                ? Map.of()
                                : Map.of(
                                        f.a,
                                        new Fraction(
                                                v < 3 || v == 8
                                                        ? 0
                                                        : v < 5 ? 75 : v == 5 ? -30 : -60,
                                                1),
                                        f.b,
                                        new Fraction(
                                                v < 3 || v == 8
                                                        ? 0
                                                        : v < 5 ? -75 : v == 5 ? 30 : 60,
                                                1));
                assertEquals(expected, balance.orElseThrow().values());
            }
        }
        assertEquals(historical, f.tx.read(context -> f.history.atVersion(f.p, 3).orElseThrow()));
        assertEquals(oldBalance, d.balance(3));
        assertEquals(9, f.jdbc.queryForObject("select count(*) from v3_pot_event", Integer.class));
        assertEquals(
                9,
                f.jdbc.queryForObject(
                        "select count(*) from v3_command_completion_event", Integer.class));
        // Every published component matches a real revision at the event's V, and vice versa.
        for (String component :
                List.of(
                        "POT_HEADER",
                        "POT_DETAILS",
                        "SHAREHOLDER_HEADER",
                        "SHAREHOLDER_DETAILS",
                        "SHAREHOLDER_WEIGHT",
                        "EXPENSE_HEADER",
                        "EXPENSE_DETAILS",
                        "EXPENSE_FINANCIALS")) {
            var table = "v3_" + component.toLowerCase(Locale.ROOT);
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "select count(*) from "
                                    + table
                                    + " r where not exists(select 1 from v3_pot_event e join"
                                    + " v3_pot_event_component c on c.event_id=e.event_id where"
                                    + " e.pot_id=r.pot_id and e.subject_version=r.active_from and"
                                    + " c.kind=? and c.object_id=r.object_id and"
                                    + " c.local_version=r.local_version)",
                            Integer.class,
                            component));
        }
        f.dense();
    }

    @Test
    void e02D01MNoopsRejectionsAndHistoricalDeletedShareholder() throws Exception {
        f.run(f.command(0, new CreatePot(new Label("P"))));
        f.run(
                f.command(
                        1,
                        new AddShareholders(
                                List.of(
                                        new ShareholderTransitions.Creation(
                                                f.a, new Name("A"), Optional.empty(), f.one)))));
        var old = f.tx.read(context -> f.history.atVersion(f.p, 2).orElseThrow());
        d.weight(2, f.a, 3, 1);
        var noop =
                f.run(
                        f.command(
                                3,
                                new UpdateShareholderWeights(
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(
                                                        f.a, new Weight(new Fraction(3, 1)))))));
        assertEquals(Outcome.UNCHANGED, noop.outcome());
        assertEquals(3L, noop.resultingPotVersion());
        f.rejected(
                f.run(
                        f.command(
                                2,
                                new UpdateShareholderWeights(
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(
                                                        f.a, new Weight(new Fraction(3, 1))))))),
                "OBSOLETE");
        f.run(f.command(3, new DeleteShareholder(f.a)));
        var after = f.snapshot().shareholders().get(f.a);
        assertEquals(new Versioned<>(2, 4, new ShareholderHeader(f.p, f.a, true)), after.header());
        assertEquals(old.shareholders().get(f.a).details(), after.details());
        assertEquals(
                new Versioned<>(
                        2, 3, new ShareholderWeight(f.p, f.a, new Weight(new Fraction(3, 1)))),
                after.weight());
        assertEquals(Map.of(f.a, Fraction.ZERO), d.balance(4).values());
        f.rejected(
                f.run(
                        f.command(
                                4,
                                new UpdateShareholderDetails(
                                        List.of(
                                                new ShareholderTransitions.DetailsIntent(
                                                        f.a, new Name("X"), Optional.empty()))))),
                "DELETED");
        assertEquals(old, f.tx.read(context -> f.history.atVersion(f.p, 2).orElseThrow()));
        assertEquals(4, f.jdbc.queryForObject("select count(*) from v3_pot_event", Integer.class));
        assertEquals(
                7,
                f.jdbc.queryForObject(
                        "select count(*) from v3_command_completion_event", Integer.class));
        assertEquals(4, f.storeVersion());
        f.dense();
    }

    @Test
    void e03FinancialNoopObsoleteEqualityAndReplayHaveOnlyCompletions() throws Exception {
        f.base();
        var financial = d.financial(100, f.a, Optional.empty());
        f.run(f.command(2, f.expense(financial)));
        var original = f.snapshot();
        var old = d.balance(3);
        var noop = f.command(3, new UpdateExpenseFinancials(financial));
        var token = f.admit(noop);
        var attempt = f.engine().execute(noop.requestId(), token);
        assertEquals(Outcome.UNCHANGED, attempt.result().orElseThrow().outcome());
        assertEquals(attempt, f.engine().execute(noop.requestId(), token));
        assertEquals(original, f.snapshot());
        var revised = d.financial(120, f.a, Optional.empty());
        f.run(f.command(3, new UpdateExpenseFinancials(revised)));
        f.rejected(f.run(f.command(3, new UpdateExpenseFinancials(revised))), "OBSOLETE");
        assertEquals(
                Outcome.UNCHANGED,
                f.run(f.command(4, new UpdateExpenseFinancials(revised))).outcome());
        f.rejected(f.run(f.command(4, new DeleteShareholder(f.b))), "REFERENCED");
        d.exact(4, "120/1", "-60/1", "-60/1");
        assertEquals(old, d.balance(3));
        assertEquals(4, f.storeVersion());
        assertEquals(4, f.jdbc.queryForObject("select count(*) from v3_pot_event", Integer.class));
        assertEquals(
                8,
                f.jdbc.queryForObject(
                        "select count(*) from v3_command_completion_event", Integer.class));
        assertEquals(
                List.of(Map.of("kind", "EXPENSE_FINANCIALS", "local_version", 2L)),
                f.jdbc.queryForList(
                        "select c.kind,c.local_version from v3_pot_event_component c join"
                                + " v3_pot_event e using(event_id) where e.subject_version=4"));
        f.dense();
    }

    @Test
    void e04OldCreationObservationMixedBatchAndExpenseDeletionDependencies() throws Exception {
        f.base();
        var before = f.snapshot();
        f.run(
                f.command(
                        2,
                        new UpdateShareholderWeights(
                                List.of(
                                        new ShareholderTransitions.WeightIntent(f.a, f.two),
                                        new ShareholderTransitions.WeightIntent(f.b, f.one),
                                        new ShareholderTransitions.WeightIntent(f.c, f.one)))));
        var mixed = f.snapshot();
        assertEquals(3, f.storeVersion());
        assertEquals(
                new Versioned<>(2, 3, new ShareholderWeight(f.p, f.a, f.two)),
                mixed.shareholders().get(f.a).weight());
        for (var id : List.of(f.b, f.c))
            assertEquals(before.shareholders().get(id), mixed.shareholders().get(id));
        assertEquals(
                List.of(
                        Map.of(
                                "kind",
                                "SHAREHOLDER_WEIGHT",
                                "object_id",
                                f.a.value(),
                                "local_version",
                                2L)),
                f.jdbc.queryForList(
                        "select c.kind,c.object_id,c.local_version from v3_pot_event_component c"
                            + " join v3_pot_event e using(event_id) where e.subject_version=3"));
        f.rejected(
                f.run(
                        f.command(
                                2,
                                new UpdateShareholderWeights(
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(f.b, f.two),
                                                new ShareholderTransitions.WeightIntent(
                                                        f.a, f.two))))),
                "OBSOLETE");
        assertEquals(
                mixed, f.snapshot()); // Last requested target equals its stale value; first target
        // cannot leak.
        var late = new com.kartaguez.pocoma.domain.pot.value.id.ShareholderId(new UUID(12, 44));
        assertEquals(
                4L,
                f.run(
                                f.command(
                                        2,
                                        new AddShareholders(
                                                List.of(
                                                        new ShareholderTransitions.Creation(
                                                                late,
                                                                new Name("late"),
                                                                Optional.empty(),
                                                                f.one)))))
                        .resultingPotVersion());
        var financial = d.financial(100, f.a, Optional.empty());
        assertEquals(5L, f.run(f.command(2, f.expense(financial))).resultingPotVersion());
        d.exact(3, "0/1", "0/1", "0/1");
        f.run(f.command(5, new UpdateExpenseFinancials(d.financial(120, f.a, Optional.empty()))));
        f.rejected(f.run(f.command(5, new DeleteExpense(f.e))), "OBSOLETE");
        d.weight(6, f.b, 3, 1);
        assertEquals(8L, f.run(f.command(6, new DeleteExpense(f.e))).resultingPotVersion());
        // A weight change alone leaves all three Expense dependencies valid for deletion.
        assertEquals(new Fraction(120, 1), d.balance(7).values().get(f.a));
        assertEquals(new Fraction(-90, 1), d.balance(7).values().get(f.b));
        assertEquals(new Fraction(-30, 1), d.balance(7).values().get(f.c));
        assertEquals(Fraction.ZERO, d.balance(7).values().get(late));
        assertTrue(d.balance(8).values().values().stream().allMatch(Fraction.ZERO::equals));
        assertEquals(1, f.snapshot().expenses().get(f.e).details().localVersion());
        assertEquals(2, f.snapshot().expenses().get(f.e).financials().localVersion());
        f.dense();
    }
}
