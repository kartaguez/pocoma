package com.kartaguez.pocoma.domain.pot.v3;

import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.util.*;

/**
 * Full exact calculation, independent of any previous balance, iteration order or current state.
 */
public final class PotBalanceCalculator {
    public record Balances(PotId potId, long version, Map<ShareholderId, Fraction> values) {
        public Balances {
            Objects.requireNonNull(potId);
            if (version < 1) throw new IllegalArgumentException("Positive version required");
            var ordered =
                    new TreeMap<ShareholderId, Fraction>(
                            Comparator.comparing(id -> id.value().toString()));
            ordered.putAll(values);
            values = Collections.unmodifiableMap(ordered);
        }
    }

    /** Terminal D has intentionally no balance; every earlier active version remains calculable. */
    public Optional<Balances> calculate(PotFinancialState state) {
        Objects.requireNonNull(state);
        if (state.header().value().deleted()) return Optional.empty();
        var balances = new HashMap<ShareholderId, Fraction>();
        // Historical composition includes deleted Shareholders: keep their canonical zero too.
        state.shareholders().keySet().forEach(id -> balances.put(id, Fraction.ZERO));
        for (var expense : state.expenses().values()) {
            if (expense.header().value().deleted()) continue;
            var financials = expense.financials().value();
            Fraction total = Fraction.ZERO;
            for (var participant : financials.participants().entrySet())
                total =
                        total.add(
                                effectiveWeight(
                                        state, participant.getKey(), participant.getValue()));
            Fraction amount = financials.amount().value();
            balances.merge(financials.payerId(), amount, Fraction::add);
            // Exact distributivity: compute the common negative factor once per Expense.
            Fraction debitPerWeight = Fraction.ZERO.subtract(amount.divide(total));
            for (var participant : financials.participants().entrySet()) {
                var weight = effectiveWeight(state, participant.getKey(), participant.getValue());
                balances.merge(
                        participant.getKey(), debitPerWeight.multiply(weight), Fraction::add);
            }
        }
        return Optional.of(new Balances(state.potId(), state.version(), balances));
    }

    private static Fraction effectiveWeight(
            PotFinancialState state, ShareholderId id, Optional<Weight> specific) {
        return specific.orElseGet(
                        () -> state.shareholders().get(id).weight().value().defaultWeight())
                .value();
    }
}
