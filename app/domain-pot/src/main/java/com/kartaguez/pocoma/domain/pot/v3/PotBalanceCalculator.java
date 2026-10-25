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
            var weights = new HashMap<ShareholderId, Fraction>();
            financials
                    .participants()
                    .forEach(
                            (id, specific) ->
                                    weights.put(
                                            id,
                                            specific.orElse(
                                                            state.shareholders()
                                                                    .get(id)
                                                                    .weight()
                                                                    .value()
                                                                    .defaultWeight())
                                                    .value()));
            Fraction total = weights.values().stream().reduce(Fraction.ZERO, Fraction::add);
            Fraction amount = financials.amount().value();
            balances.merge(financials.payerId(), amount, Fraction::add);
            weights.forEach(
                    (id, weight) ->
                            balances.merge(
                                    id,
                                    Fraction.ZERO.subtract(amount.multiply(weight).divide(total)),
                                    Fraction::add));
        }
        return Optional.of(new Balances(state.potId(), state.version(), balances));
    }
}
