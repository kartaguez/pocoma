package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.PotHeader;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.revision.Versioned;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderWeight;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PotBalanceAggregateEquivalenceTest {

    private final PotId potId = new PotId(new UUID(1L, 1L));
    private final ShareholderId s1 = sh(1);
    private final ShareholderId s2 = sh(2);
    private final ShareholderId s3 = sh(3);
    private final ShareholderId s4 = sh(4);
    private final ShareholderId s5 = sh(5);

    private final PotBalanceCalculator oracle = new PotBalanceCalculator();
    private final PotBalanceAggregateCalculator aggregateCalc = new PotBalanceAggregateCalculator();

    private static ShareholderId sh(long n) {
        return new ShareholderId(new UUID(2L, n));
    }

    private static ExpenseId exp(long n) {
        return new ExpenseId(new UUID(3L, n));
    }

    private static Weight w(long n, long d) {
        return new Weight(new Fraction(n, d));
    }

    private ExpenseFinancials expense(
            long n,
            Fraction amount,
            ShareholderId payer,
            Map<ShareholderId, Optional<Weight>> participants) {
        return new ExpenseFinancials(potId, exp(n), new Amount(amount), payer, participants);
    }

    private PotFinancialState activeState(
            long version,
            Map<ShareholderId, Weight> weights,
            Set<ShareholderId> deletedShareholders,
            List<ExpenseFinancials> activeExpenses,
            List<ExpenseFinancials> deletedExpenses) {
        var ss = new LinkedHashMap<ShareholderId, PotFinancialState.Shareholder>();
        weights.forEach(
                (id, weight) ->
                        ss.put(
                                id,
                                new PotFinancialState.Shareholder(
                                        new Versioned<>(
                                                1,
                                                1,
                                                new ShareholderHeader(
                                                        potId, id, deletedShareholders.contains(id))),
                                        new Versioned<>(
                                                1, 1, new ShareholderWeight(potId, id, weight)))));
        var es = new LinkedHashMap<ExpenseId, PotFinancialState.Expense>();
        for (var e : activeExpenses) {
            es.put(
                    e.expenseId(),
                    new PotFinancialState.Expense(
                            new Versioned<>(1, 1, new ExpenseHeader(potId, e.expenseId(), false)),
                            new Versioned<>(1, 1, e)));
        }
        for (var e : deletedExpenses) {
            es.put(
                    e.expenseId(),
                    new PotFinancialState.Expense(
                            new Versioned<>(2, 1, new ExpenseHeader(potId, e.expenseId(), true)),
                            new Versioned<>(1, 1, e)));
        }
        return new PotFinancialState(
                potId, version, new Versioned<>(1, 1, new PotHeader(potId, false)), ss, es);
    }

    private PotFinancialState activeState(
            long version, Map<ShareholderId, Weight> weights, List<ExpenseFinancials> expenses) {
        return activeState(version, weights, Set.of(), expenses, List.of());
    }

    private static Map<UUID, Weight> uuidWeights(Map<ShareholderId, Weight> weights) {
        var map = new LinkedHashMap<UUID, Weight>();
        weights.forEach((k, v) -> map.put(k.value(), v));
        return map;
    }

    private PotBalance oracleBalance(PotFinancialState state) {
        var b = oracle.calculate(state).orElseThrow();
        var map = new HashMap<UUID, Fraction>();
        b.values().forEach((k, v) -> map.put(k.value(), v));
        return new PotBalance(b.potId().value(), b.version(), map);
    }

    /** Canonical wire format matching JdbcPotBalanceStore.encode(PotBalance). */
    private static String canonicalJson(PotBalance balance) {
        return balance.values().entrySet().stream()
                .map(
                        e ->
                                "{\"id\":\""
                                        + e.getKey()
                                        + "\",\"n\":\""
                                        + e.getValue().numerator()
                                        + "\",\"d\":\""
                                        + e.getValue().denominator()
                                        + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void assertStrictEquivalence(PotFinancialState state) {
        var expectedOpt = oracle.calculate(state);
        var aggOpt = aggregateCalc.fromState(state);
        assertEquals(expectedOpt.isPresent(), aggOpt.isPresent());
        if (expectedOpt.isEmpty()) return;

        var expected = oracleBalance(state);
        var agg = aggOpt.get();
        var fromStoredWeights = aggregateCalc.calculate(agg);
        var weights = new LinkedHashMap<UUID, Weight>();
        state.shareholders()
                .forEach((id, s) -> weights.put(id.value(), s.weight().value().defaultWeight()));
        var fromExplicitWeights = aggregateCalc.calculate(agg, weights);

        assertExactBalanceEquals(expected, fromStoredWeights);
        assertExactBalanceEquals(expected, fromExplicitWeights);
    }

    private static void assertExactBalanceEquals(PotBalance expected, PotBalance actual) {
        assertEquals(expected.potId(), actual.potId());
        assertEquals(expected.version(), actual.version());
        assertEquals(
                new ArrayList<>(expected.values().keySet()),
                new ArrayList<>(actual.values().keySet()),
                "Canonical shareholder key ordering must match");
        expected.values()
                .forEach(
                        (id, expVal) -> {
                            var actVal = actual.values().get(id);
                            assertNotNull(actVal);
                            assertEquals(expVal.numerator(), actVal.numerator(), "numerator " + id);
                            assertEquals(
                                    expVal.denominator(), actVal.denominator(), "denominator " + id);
                        });
        assertEquals(canonicalJson(expected), canonicalJson(actual));
    }

    @Test
    void pureDefaultWeightsSubsetsAndSharedBuckets() {
        var weights = Map.of(s1, w(1, 1), s2, w(2, 1), s3, w(3, 1), s4, w(5, 2));
        var e1 = expense(1, new Fraction(100, 1), s1, Map.of(s1, Optional.empty(), s2, Optional.empty()));
        var e2 = expense(2, new Fraction(50, 1), s2, Map.of(s1, Optional.empty(), s2, Optional.empty()));
        var e3 = expense(3, new Fraction(75, 1), s4, Map.of(s2, Optional.empty(), s3, Optional.empty()));
        var state = activeState(1, weights, List.of(e1, e2, e3));

        var agg = aggregateCalc.fromState(state).orElseThrow();
        assertEquals(2, agg.bucketCount(), "e1 and e2 share one bucket; e3 forms a second bucket");
        assertStrictEquivalence(state);
    }

    @Test
    void pureSpecificWeightsCollapseIntoFixedNetAndIgnoreDefaultWeightChanges() {
        var initialWeights = Map.of(s1, w(1, 1), s2, w(1, 1), s3, w(1, 1));
        var e1 =
                expense(
                        1,
                        new Fraction(100, 1),
                        s1,
                        Map.of(s2, Optional.of(w(1, 3)), s3, Optional.of(w(2, 3))));
        var e2 =
                expense(
                        2,
                        new Fraction(33, 7),
                        s3,
                        Map.of(s1, Optional.of(w(5, 2)), s3, Optional.of(w(3, 2))));
        var state1 = activeState(1, initialWeights, List.of(e1, e2));

        var agg = aggregateCalc.fromState(state1).orElseThrow();
        assertEquals(0, agg.bucketCount(), "Pure specific expenses create zero buckets");
        assertStrictEquivalence(state1);

        var updatedWeights = Map.of(s1, w(9, 1), s2, w(7, 5), s3, w(11, 3));
        var state2 = activeState(1, updatedWeights, List.of(e1, e2));
        assertExactBalanceEquals(
                oracleBalance(state2), aggregateCalc.calculate(agg, uuidWeights(updatedWeights)));
    }

    @Test
    void mixedExpensesShareBucketWhenDefaultSetAndSpecificTotalMatch() {
        var weights = Map.of(s1, w(1, 2), s2, w(3, 2), s3, w(5, 1), s4, w(7, 1));
        // Both have B_def = {s1, s2} and W_spec = 2, even though specific participants differ (s3 vs s4).
        var e1 =
                expense(
                        1,
                        new Fraction(100, 1),
                        s1,
                        Map.of(s1, Optional.empty(), s2, Optional.empty(), s3, Optional.of(w(2, 1))));
        var e2 =
                expense(
                        2,
                        new Fraction(60, 1),
                        s4,
                        Map.of(s1, Optional.empty(), s2, Optional.empty(), s4, Optional.of(w(2, 1))));
        // Different W_spec = 5/2 -> separate bucket.
        var e3 =
                expense(
                        3,
                        new Fraction(45, 1),
                        s2,
                        Map.of(s1, Optional.empty(), s2, Optional.empty(), s3, Optional.of(w(5, 2))));
        var state = activeState(3, weights, List.of(e1, e2, e3));

        var agg = aggregateCalc.fromState(state).orElseThrow();
        assertEquals(2, agg.bucketCount());
        assertStrictEquivalence(state);

        // Retroactive default weight change on s1 and s2 must match oracle identically.
        var nextWeights = Map.of(s1, w(7, 3), s2, w(2, 5), s3, w(5, 1), s4, w(7, 1));
        var nextState = activeState(3, nextWeights, List.of(e1, e2, e3));
        assertExactBalanceEquals(
                oracleBalance(nextState), aggregateCalc.calculate(agg, uuidWeights(nextWeights)));
    }

    @Test
    void addThenRemoveIsExactIdentityAndPrunesEmptyBuckets() {
        var weights = Map.of(s1, w(1, 1), s2, w(2, 1), s3, w(3, 1), s4, w(4, 1));
        var base = aggregateCalc.fromState(activeState(1, weights, List.of())).orElseThrow();
        assertEquals(0, base.bucketCount());

        var pureDefault =
                expense(1, new Fraction(120, 1), s1, Map.of(s2, Optional.empty(), s3, Optional.empty()));
        var pureSpecific =
                expense(
                        2,
                        new Fraction(55, 3),
                        s2,
                        Map.of(s1, Optional.of(w(3, 5)), s4, Optional.of(w(7, 5))));
        var mixed =
                expense(
                        3,
                        new Fraction(89, 7),
                        s3,
                        Map.of(
                                s1,
                                Optional.empty(),
                                s2,
                                Optional.empty(),
                                s3,
                                Optional.of(w(1, 4)),
                                s4,
                                Optional.of(w(3, 4))));
        var zeroAmount =
                expense(4, Fraction.ZERO, s1, Map.of(s1, Optional.empty(), s2, Optional.empty()));

        var withAll =
                aggregateCalc.addExpense(
                        aggregateCalc.addExpense(
                                aggregateCalc.addExpense(
                                        aggregateCalc.addExpense(base, pureDefault), pureSpecific),
                                mixed),
                        zeroAmount);
        assertStrictEquivalence(
                activeState(1, weights, List.of(pureDefault, pureSpecific, mixed, zeroAmount)));

        var afterRemovals =
                aggregateCalc.removeExpense(
                        aggregateCalc.removeExpense(
                                aggregateCalc.removeExpense(
                                        aggregateCalc.removeExpense(withAll, mixed), pureSpecific),
                                pureDefault),
                        zeroAmount);
        assertEquals(base, afterRemovals);
        assertEquals(0, afterRemovals.bucketCount());
        for (var entry : afterRemovals.shareholders()) {
            assertEquals(Fraction.ZERO, entry.fixedNet());
        }
    }

    @Test
    void modificationsOfAmountPayerBeneficiariesSpecificWeightsAndMultiStepDeltas() {
        var w1 = Map.of(s1, w(1, 1), s3, w(2, 1), s5, w(3, 1));
        var e1v1 =
                expense(
                        1,
                        new Fraction(100, 1),
                        s1,
                        Map.of(s1, Optional.empty(), s3, Optional.empty()));
        var e2v1 =
                expense(
                        2,
                        new Fraction(50, 1),
                        s3,
                        Map.of(s3, Optional.empty(), s5, Optional.of(w(3, 2))));
        var stateK = activeState(2, w1, List.of(e1v1, e2v1));
        var aggK = aggregateCalc.fromState(stateK).orElseThrow();

        // Between K=2 and N=7:
        // - New shareholders s2 and s4 are added (forcing canonical index re-mapping for s3 and s5!).
        // - s1's default weight changes retroactively.
        // - e1 changes amount, payer, and beneficiaries (includes new s2).
        // - e2 changes specific weight of s5.
        // - e3 is added (pure specific).
        var wN = Map.of(s1, w(5, 2), s2, w(3, 4), s3, w(2, 1), s4, w(7, 3), s5, w(3, 1));
        var e1vN =
                expense(
                        1,
                        new Fraction(240, 1),
                        s2,
                        Map.of(s1, Optional.empty(), s2, Optional.empty(), s3, Optional.empty()));
        var e2vN =
                expense(
                        2,
                        new Fraction(50, 1),
                        s3,
                        Map.of(s3, Optional.empty(), s5, Optional.of(w(5, 2))));
        var e3vN =
                expense(
                        3,
                        new Fraction(19, 11),
                        s4,
                        Map.of(s1, Optional.of(w(2, 7)), s5, Optional.of(w(5, 7))));

        var aggNFromDelta =
                aggregateCalc.applyDelta(
                        aggK,
                        7L,
                        Map.of(
                                s1.value(), w(5, 2),
                                s2.value(), w(3, 4),
                                s4.value(), w(7, 3)),
                        List.of(e1v1, e2v1),
                        List.of(e1vN, e2vN, e3vN));

        var stateN = activeState(7, wN, List.of(e1vN, e2vN, e3vN));
        var aggNFull = aggregateCalc.fromState(stateN).orElseThrow();

        assertEquals(aggNFull, aggNFromDelta, "Delta composition must equal full aggregate rebuild");
        assertExactBalanceEquals(oracleBalance(stateN), aggregateCalc.calculate(aggNFromDelta));
    }

    @Test
    void permutationInvarianceAndLargeNonTrivialRationals() {
        BigInteger huge = BigInteger.TEN.pow(36).add(BigInteger.valueOf(97));
        var weights =
                Map.of(
                        s1, new Weight(new Fraction(huge, BigInteger.valueOf(13))),
                        s2, new Weight(Fraction.decimal("0.125")),
                        s3, w(17, 19),
                        s4, w(23, 29));
        var expenses =
                new ArrayList<>(
                        List.of(
                                expense(
                                        1,
                                        new Fraction(huge, BigInteger.valueOf(7)),
                                        s1,
                                        Map.of(s1, Optional.empty(), s2, Optional.empty())),
                                expense(
                                        2,
                                        new Fraction(31, 37),
                                        s2,
                                        Map.of(
                                                s1,
                                                Optional.empty(),
                                                s2,
                                                Optional.empty(),
                                                s3,
                                                Optional.of(w(41, 43)))),
                                expense(
                                        3,
                                        new Fraction(1000003, 997),
                                        s3,
                                        Map.of(
                                                s2,
                                                Optional.of(w(53, 59)),
                                                s4,
                                                Optional.of(w(61, 67)))),
                                expense(
                                        4,
                                        Fraction.decimal("12345.6789"),
                                        s4,
                                        Map.of(
                                                s1,
                                                Optional.empty(),
                                                s3,
                                                Optional.empty(),
                                                s4,
                                                Optional.empty()))));

        var referenceState = activeState(5, weights, expenses);
        var referenceAgg = aggregateCalc.fromState(referenceState).orElseThrow();
        var referenceBalance = oracleBalance(referenceState);
        assertExactBalanceEquals(referenceBalance, aggregateCalc.calculate(referenceAgg));
        assertTrue(referenceBalance.values().get(s1.value()).numerator().bitLength() > 63);

        var random = new Random(42L);
        for (int trial = 0; trial < 20; trial++) {
            Collections.shuffle(expenses, random);
            var permutedState = activeState(5, weights, expenses);
            var permutedAgg = aggregateCalc.fromState(permutedState).orElseThrow();
            assertEquals(referenceAgg, permutedAgg, "Aggregate must be invariant to expense order");
            assertExactBalanceEquals(referenceBalance, aggregateCalc.calculate(permutedAgg));
        }
    }

    @Test
    void deletedPotDeletedShareholdersAndDeletedExpenses() {
        var weights = Map.of(s1, w(1, 1), s2, w(2, 1), s3, w(3, 1));
        var activeExp =
                expense(1, new Fraction(90, 1), s1, Map.of(s1, Optional.empty(), s2, Optional.empty()));
        var deletedExp =
                expense(2, new Fraction(40, 1), s3, Map.of(s1, Optional.empty(), s3, Optional.empty()));

        var stateWithDeleted =
                activeState(4, weights, Set.of(s3), List.of(activeExp), List.of(deletedExp));
        assertStrictEquivalence(stateWithDeleted);
        var agg = aggregateCalc.fromState(stateWithDeleted).orElseThrow();
        assertEquals(Fraction.ZERO, aggregateCalc.calculate(agg).values().get(s3.value()));

        var terminalPotState =
                new PotFinancialState(
                        potId,
                        5,
                        new Versioned<>(2, 5, new PotHeader(potId, true)),
                        stateWithDeleted.shareholders(),
                        stateWithDeleted.expenses());
        assertTrue(oracle.calculate(terminalPotState).isEmpty());
        assertTrue(aggregateCalc.fromState(terminalPotState).isEmpty());
    }

    @Test
    void deterministicDifferentialSuiteAcrossManyValidStatesAndDeltaChains() {
        var rng = new Random(7312026L);
        for (int scenario = 0; scenario < 150; scenario++) {
            int shareholderCount = 2 + rng.nextInt(10);
            var ids = new ArrayList<ShareholderId>(shareholderCount);
            var weights = new LinkedHashMap<ShareholderId, Weight>();
            for (int i = 0; i < shareholderCount; i++) {
                var id = new ShareholderId(new UUID(100L + scenario, rng.nextLong()));
                ids.add(id);
                weights.put(id, randomWeight(rng));
            }

            int expenseCount = rng.nextInt(25);
            var expenses = new ArrayList<ExpenseFinancials>(expenseCount);
            for (int e = 1; e <= expenseCount; e++) {
                expenses.add(randomExpense(rng, e, ids));
            }

            var state1 = activeState(1, weights, expenses);
            assertStrictEquivalence(state1);
            var agg1 = aggregateCalc.fromState(state1).orElseThrow();

            // Retroactive weight change on random subset of shareholders.
            var nextWeights = new LinkedHashMap<>(weights);
            var weightUpdates = new LinkedHashMap<UUID, Weight>();
            for (var id : ids) {
                if (rng.nextBoolean()) {
                    var nw = randomWeight(rng);
                    nextWeights.put(id, nw);
                    weightUpdates.put(id.value(), nw);
                }
            }
            // Occasionally add a new shareholder.
            if (rng.nextInt(3) == 0) {
                var addedId = new ShareholderId(new UUID(200L + scenario, rng.nextLong()));
                var addedWeight = randomWeight(rng);
                ids.add(addedId);
                nextWeights.put(addedId, addedWeight);
                weightUpdates.put(addedId.value(), addedWeight);
            }

            // Remove some expenses, modify some, and add new ones.
            var removed = new ArrayList<ExpenseFinancials>();
            var added = new ArrayList<ExpenseFinancials>();
            var nextExpenses = new ArrayList<ExpenseFinancials>();
            int nextExpNum = expenseCount + 1;
            for (var existing : expenses) {
                int action = rng.nextInt(5);
                if (action == 0) {
                    removed.add(existing);
                } else if (action == 1) {
                    removed.add(existing);
                    var modified = randomExpense(rng, existing.expenseId().value().getLeastSignificantBits(), ids);
                    added.add(modified);
                    nextExpenses.add(modified);
                } else {
                    nextExpenses.add(existing);
                }
            }
            int additions = rng.nextInt(4);
            for (int a = 0; a < additions; a++) {
                var created = randomExpense(rng, nextExpNum++, ids);
                added.add(created);
                nextExpenses.add(created);
            }

            long targetVersion = 2L + rng.nextInt(20);
            var state2 = activeState(targetVersion, nextWeights, nextExpenses);
            var agg2Full = aggregateCalc.fromState(state2).orElseThrow();
            var agg2Delta =
                    aggregateCalc.applyDelta(agg1, targetVersion, weightUpdates, removed, added);

            assertEquals(agg2Full, agg2Delta, "Scenario " + scenario + " delta vs full aggregate");
            assertExactBalanceEquals(
                    oracleBalance(state2),
                    aggregateCalc.calculate(agg2Delta, uuidWeights(nextWeights)));
        }
    }

    @Test
    void invariantsAndCanonicalizationGuardsRejectInvalidAggregatesAndOperations() {
        UUID u1 = s1.value();
        UUID u2 = s2.value();

        // Non-canonical shareholder ordering rejected.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PotBalanceAggregate(
                                potId.value(),
                                1,
                                List.of(
                                        new ShareholderEntry(u2, Fraction.ONE, false, Fraction.ZERO),
                                        new ShareholderEntry(u1, Fraction.ONE, false, Fraction.ZERO)),
                                Map.of()));

        // Global conservation violation: sum(fixedNet) != sum(bucketAmounts).
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PotBalanceAggregate(
                                potId.value(),
                                1,
                                List.of(
                                        new ShareholderEntry(u1, Fraction.ONE, false, Fraction.ONE),
                                        new ShareholderEntry(u2, Fraction.ONE, false, Fraction.ZERO)),
                                Map.of()));

        // Overlapping default and specific participant in same bucket rejected.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PotBalanceAggregate(
                                potId.value(),
                                1,
                                List.of(
                                        new ShareholderEntry(u1, Fraction.ONE, false, new Fraction(10, 1)),
                                        new ShareholderEntry(u2, Fraction.ONE, false, Fraction.ZERO)),
                                Map.of(
                                        new BucketKey(List.of(0), Fraction.ONE),
                                        new BucketValue(
                                                new Fraction(10, 1), Map.of(0, new Fraction(10, 1))))));

        // Bucket specific-weight conservation violation: sum(Q) != T * W_spec.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PotBalanceAggregate(
                                potId.value(),
                                1,
                                List.of(
                                        new ShareholderEntry(u1, Fraction.ONE, false, new Fraction(10, 1)),
                                        new ShareholderEntry(u2, Fraction.ONE, false, Fraction.ZERO)),
                                Map.of(
                                        new BucketKey(List.of(0), new Fraction(2, 1)),
                                        new BucketValue(
                                                new Fraction(10, 1), Map.of(1, new Fraction(15, 1))))));

        // Removing an absent expense rejected.
        var empty =
                aggregateCalc
                        .fromState(activeState(1, Map.of(s1, w(1, 1), s2, w(1, 1)), List.of()))
                        .orElseThrow();
        var absent =
                expense(1, new Fraction(10, 1), s1, Map.of(s1, Optional.empty(), s2, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class, () -> aggregateCalc.removeExpense(empty, absent));

        // Missing weight during evaluation rejected.
        var withOne = aggregateCalc.addExpense(empty, absent);
        assertThrows(
                IllegalArgumentException.class,
                () -> aggregateCalc.calculate(withOne, Map.of(u1, w(1, 1))));
    }

    private Weight randomWeight(Random rng) {
        return new Weight(new Fraction(1 + rng.nextInt(11), 1 + rng.nextInt(7)));
    }

    private ExpenseFinancials randomExpense(Random rng, long num, List<ShareholderId> ids) {
        Fraction amount =
                rng.nextInt(12) == 0
                        ? Fraction.ZERO
                        : new Fraction(1 + rng.nextInt(500), 1 + rng.nextInt(7));
        var payer = ids.get(rng.nextInt(ids.size()));
        var participants = new LinkedHashMap<ShareholderId, Optional<Weight>>();
        int mode = rng.nextInt(4); // 0=pure default, 1=pure specific, 2=mixed, 3=subset mixed
        for (var id : ids) {
            if (rng.nextBoolean() || participants.isEmpty()) {
                Optional<Weight> spec =
                        switch (mode) {
                            case 0 -> Optional.empty();
                            case 1 -> Optional.of(randomWeight(rng));
                            default -> rng.nextInt(3) == 0
                                    ? Optional.of(randomWeight(rng))
                                    : Optional.empty();
                        };
                participants.put(id, spec);
            }
        }
        return new ExpenseFinancials(potId, exp(num), new Amount(amount), payer, participants);
    }
}
