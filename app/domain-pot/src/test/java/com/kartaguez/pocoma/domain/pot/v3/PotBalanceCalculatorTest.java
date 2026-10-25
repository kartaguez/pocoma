package com.kartaguez.pocoma.domain.pot.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.id.*;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

class PotBalanceCalculatorTest {
    final PotId p = new PotId(new UUID(1, 1));
    final ShareholderId a = id(1), b = id(2), c = id(3), z = id(4);
    final PotBalanceCalculator calculator = new PotBalanceCalculator();

    static ShareholderId id(long n) {
        return new ShareholderId(new UUID(2, n));
    }

    static Weight w(long n, long d) {
        return new Weight(new Fraction(n, d));
    }

    PotFinancialState state(
            long version, Map<ShareholderId, Weight> weights, List<ExpenseFinancials> fs) {
        var ss = new LinkedHashMap<ShareholderId, PotFinancialState.Shareholder>();
        weights.forEach(
                (id, weight) ->
                        ss.put(
                                id,
                                new PotFinancialState.Shareholder(
                                        new Versioned<>(1, 1, new ShareholderHeader(p, id, false)),
                                        new Versioned<>(
                                                1, 1, new ShareholderWeight(p, id, weight)))));
        var es = new LinkedHashMap<ExpenseId, PotFinancialState.Expense>();
        fs.forEach(
                f ->
                        es.put(
                                f.expenseId(),
                                new PotFinancialState.Expense(
                                        new Versioned<>(
                                                1, 1, new ExpenseHeader(p, f.expenseId(), false)),
                                        new Versioned<>(1, 1, f))));
        return new PotFinancialState(
                p, version, new Versioned<>(1, 1, new PotHeader(p, false)), ss, es);
    }

    ExpenseFinancials f(
            int n,
            Fraction amount,
            ShareholderId payer,
            Map<ShareholderId, Optional<Weight>> participants) {
        return new ExpenseFinancials(
                p, new ExpenseId(new UUID(3, n)), new Amount(amount), payer, participants);
    }

    Map<ShareholderId, Fraction> values(PotFinancialState s) {
        return calculator.calculate(s).orElseThrow().values();
    }

    void exact(Map<ShareholderId, Fraction> actual, Map<ShareholderId, String> expected) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach(
                (id, value) ->
                        assertEquals(
                                value,
                                actual.get(id).numerator() + "/" + actual.get(id).denominator(),
                                id.toString()));
        assertEquals(Fraction.ZERO, actual.values().stream().reduce(Fraction.ZERO, Fraction::add));
    }

    @Test
    void d01PayerParticipantAndSeparatePayerExactZeros() {
        exact(
                values(
                        state(
                                1,
                                Map.of(a, w(1, 1), b, w(3, 1), z, w(1, 1)),
                                List.of(
                                        f(
                                                1,
                                                new Fraction(100, 1),
                                                a,
                                                Map.of(
                                                        a,
                                                        Optional.empty(),
                                                        b,
                                                        Optional.empty()))))),
                Map.of(a, "75/1", b, "-75/1", z, "0/1"));
        exact(
                values(
                        state(
                                1,
                                Map.of(a, w(1, 1), b, w(1, 1), c, w(2, 1), z, w(1, 1)),
                                List.of(
                                        f(
                                                1,
                                                new Fraction(100, 1),
                                                a,
                                                Map.of(
                                                        b,
                                                        Optional.empty(),
                                                        c,
                                                        Optional.empty()))))),
                Map.of(a, "100/1", b, "-100/3", c, "-200/3", z, "0/1"));
    }

    @Test
    void d02DefaultsOverridesAndIndependentHistoricalResults() {
        var weights = Map.of(a, w(1, 1), b, w(1, 2), c, w(9, 1));
        var expense =
                f(1, new Fraction(100, 1), a, Map.of(b, Optional.empty(), c, Optional.of(w(3, 2))));
        var old = state(3, weights, List.of(expense));
        var before = values(old);
        exact(before, Map.of(a, "100/1", b, "-25/1", c, "-75/1"));
        var next = state(4, Map.of(a, w(1, 1), b, w(3, 2), c, w(9, 1)), List.of(expense));
        exact(values(next), Map.of(a, "100/1", b, "-50/1", c, "-50/1"));
        var changed =
                f(1, new Fraction(100, 1), a, Map.of(b, Optional.empty(), c, Optional.of(w(1, 2))));
        exact(
                values(state(5, Map.of(a, w(1, 1), b, w(3, 2), c, w(9, 1)), List.of(changed))),
                Map.of(a, "100/1", b, "-75/1", c, "-25/1"));
        assertEquals(before, values(old));
        assertSame(expense, next.expenses().get(expense.expenseId()).financials().value());
        var specific =
                f(
                        1,
                        new Fraction(100, 1),
                        a,
                        Map.of(b, Optional.of(w(1, 1)), c, Optional.of(w(2, 1))));
        assertEquals(
                values(state(6, weights, List.of(specific))),
                values(state(7, Map.of(a, w(1, 1), b, w(5, 1), c, w(7, 1)), List.of(specific))));
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
    }

    @Test
    void d03MultipleExpensesAndRationalsBeyondMachineIntegers() {
        BigInteger big = BigInteger.TEN.pow(35).add(BigInteger.valueOf(39));
        var amount = new Fraction(big, BigInteger.valueOf(7));
        var s =
                state(
                        1,
                        Map.of(a, w(1, 1), b, w(1, 1), c, w(2, 1)),
                        List.of(
                                f(1, amount, a, Map.of(b, Optional.empty(), c, Optional.empty())),
                                f(
                                        2,
                                        new Fraction(1, 7),
                                        b,
                                        Map.of(
                                                a,
                                                Optional.of(new Weight(Fraction.decimal("0.125"))),
                                                c,
                                                Optional.of(w(3, 8))))));
        // Independent integer oracle, never the production Fraction operations.
        var expected = oracle(s);
        assertOracle(s, expected);
        assertTrue(values(s).get(a).numerator().bitLength() > 63);
        var hugeWeight = new Weight(new Fraction(big, BigInteger.valueOf(11)));
        assertOracle(
                state(
                        2,
                        Map.of(a, w(1, 1), b, hugeWeight, c, w(2, 1)),
                        List.of(f(1, amount, a, Map.of(b, Optional.empty(), c, Optional.empty())))),
                null);
    }

    @Test
    void d04DeletionTerminalAndOutOfOrderAreIndependent() {
        var initial =
                state(
                        3,
                        Map.of(a, w(1, 1), b, w(1, 1), z, w(1, 1)),
                        List.of(f(1, new Fraction(10, 1), a, Map.of(b, Optional.empty()))));
        var expense = initial.expenses().values().iterator().next();
        var deleted =
                new PotFinancialState(
                        p,
                        4,
                        initial.header(),
                        initial.shareholders(),
                        Map.of(
                                expense.header().value().expenseId(),
                                new PotFinancialState.Expense(
                                        new Versioned<>(
                                                2,
                                                4,
                                                new ExpenseHeader(
                                                        p,
                                                        expense.header().value().expenseId(),
                                                        true)),
                                        expense.financials())));
        var ss = new HashMap<>(deleted.shareholders());
        ss.put(
                b,
                new PotFinancialState.Shareholder(
                        new Versioned<>(2, 5, new ShareholderHeader(p, b, true)),
                        ss.get(b).weight()));
        var after = new PotFinancialState(p, 5, initial.header(), ss, deleted.expenses());
        exact(values(after), Map.of(a, "0/1", b, "0/1", z, "0/1"));
        assertTrue(
                calculator
                        .calculate(
                                new PotFinancialState(
                                        p,
                                        6,
                                        new Versioned<>(2, 6, new PotHeader(p, true)),
                                        ss,
                                        deleted.expenses()))
                        .isEmpty());
        for (var v : List.of(after, initial, deleted, initial)) assertOracle(v, null);
        exact(values(initial), Map.of(a, "10/1", b, "-10/1", z, "0/1"));
    }

    @Test
    void d05ZeroAndInconsistentInputsNeverProducePartialBalances() {
        exact(
                values(
                        state(
                                1,
                                Map.of(a, w(1, 1), b, w(2, 1)),
                                List.of(f(1, Fraction.ZERO, a, Map.of(b, Optional.empty()))))),
                Map.of(a, "0/1", b, "0/1"));
        assertThrows(IllegalArgumentException.class, () -> w(0, 1));
        assertThrows(IllegalArgumentException.class, () -> w(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Amount(new Fraction(-1, 1)));
        assertThrows(IllegalArgumentException.class, () -> f(1, Fraction.ONE, a, Map.of()));
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        state(
                                1,
                                Map.of(a, w(1, 1)),
                                List.of(f(1, Fraction.ONE, a, Map.of(b, Optional.empty())))));
        var valid = state(1, Map.of(a, w(1, 1), b, w(1, 1)), List.of());
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        new PotFinancialState(
                                p,
                                1,
                                new Versioned<>(2, 2, new PotHeader(p, false)),
                                valid.shareholders(),
                                Map.of()));
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        new PotFinancialState(
                                new PotId(new UUID(9, 9)),
                                1,
                                valid.header(),
                                valid.shareholders(),
                                Map.of()));
    }

    @Test
    void d06SeededSizesPermutationAndIndicativeCost() throws Exception {
        var costs = new StringBuilder("seed=731; indicative only, no performance certification\n");
        for (int size : new int[] {3, 20, 100}) {
            int count = size == 100 ? 2000 : size == 20 ? 100 : 2;
            var random = new Random(731);
            var weights = new LinkedHashMap<ShareholderId, Weight>();
            for (int i = 1; i <= size; i++)
                weights.put(id(i), w(random.nextInt(9) + 1, random.nextInt(7) + 1));
            var fs = new ArrayList<ExpenseFinancials>();
            for (int n = 1; n <= count; n++) {
                var participants = new LinkedHashMap<ShareholderId, Optional<Weight>>();
                for (int i = 1; i <= size; i++)
                    participants.put(
                            id(i), i % 3 == 0 ? Optional.of(w(i + 1, 7)) : Optional.empty());
                fs.add(f(n, new Fraction(n, 7), id(n % size + 1), participants));
            }
            var s = state(1, weights, fs);
            var expected = oracle(s);
            long start = System.nanoTime();
            var actual = values(s);
            long elapsed = System.nanoTime() - start;
            var bean =
                    (com.sun.management.ThreadMXBean)
                            java.lang.management.ManagementFactory.getThreadMXBean();
            long allocationStart = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            assertEquals(actual, values(s));
            long allocated =
                    bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
                            - allocationStart;
            assertOracle(s, expected);
            Collections.shuffle(fs, random);
            assertEquals(actual, values(state(1, weights, fs)));
            costs.append("shareholders=")
                    .append(size)
                    .append(" expenses=")
                    .append(count)
                    .append(" nanos=")
                    .append(elapsed)
                    .append(" allocatedBytes=")
                    .append(allocated)
                    .append('\n');
        }
        var output = Path.of("target/v3-pot-evidence/D-costs.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, costs);
    }

    // Deliberately independent rational implementation for expected values in generated fixtures.
    record R(BigInteger n, BigInteger d) {
        R {
            var g = n.gcd(d);
            n = n.divide(g);
            d = d.divide(g);
        }

        R add(R o) {
            return new R(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d));
        }

        R debit(R amount, R total) {
            return new R(
                    amount.n.multiply(n).multiply(total.d).negate(),
                    amount.d.multiply(d).multiply(total.n));
        }
    }

    R r(Fraction f) {
        return new R(f.numerator(), f.denominator());
    }

    Map<ShareholderId, R> oracle(PotFinancialState s) {
        var result = new HashMap<ShareholderId, R>();
        s.shareholders()
                .keySet()
                .forEach(id -> result.put(id, new R(BigInteger.ZERO, BigInteger.ONE)));
        for (var e : s.expenses().values())
            if (!e.header().value().deleted()) {
                var f = e.financials().value();
                var weights = new HashMap<ShareholderId, R>();
                f.participants()
                        .forEach(
                                (id, w) ->
                                        weights.put(
                                                id,
                                                r(
                                                        w.orElseGet(
                                                                        () ->
                                                                                s.shareholders()
                                                                                        .get(id)
                                                                                        .weight()
                                                                                        .value()
                                                                                        .defaultWeight())
                                                                .value())));
                R total = new R(BigInteger.ZERO, BigInteger.ONE);
                for (var w : weights.values()) total = total.add(w);
                result.put(f.payerId(), result.get(f.payerId()).add(r(f.amount().value())));
                for (var entry : weights.entrySet())
                    result.put(
                            entry.getKey(),
                            result.get(entry.getKey())
                                    .add(entry.getValue().debit(r(f.amount().value()), total)));
            }
        return result;
    }

    void assertOracle(PotFinancialState s, Map<ShareholderId, R> expected) {
        if (expected == null) expected = oracle(s);
        var actual = values(s);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach(
                (id, value) -> {
                    assertEquals(value.n, actual.get(id).numerator());
                    assertEquals(value.d, actual.get(id).denominator());
                });
    }
}
