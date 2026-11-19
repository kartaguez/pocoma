package com.kartaguez.pocoma.domain.projection;

import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import java.util.*;

/**
 * Pure exact operations over {@link PotBalanceAggregate}: construction from a complete
 * {@link PotFinancialState}, additive expense insertion/removal, multi-step delta composition, and
 * full-bucket balance evaluation (D3) strictly equivalent to {@code PotBalanceCalculator}.
 */
public final class PotBalanceAggregateCalculator {

    public Optional<PotBalanceAggregate> fromState(PotFinancialState state) {
        Objects.requireNonNull(state, "state");
        if (state.header().value().deleted()) {
            return Optional.empty();
        }
        var sortedShareholders = new TreeMap<UUID, ShareholderEntry>(SubobjectKey::compareUuid);
        for (var entry : state.shareholders().entrySet()) {
            UUID id = entry.getKey().value();
            var sh = entry.getValue();
            sortedShareholders.put(
                    id,
                    new ShareholderEntry(
                            id,
                            sh.weight().value().defaultWeight().value(),
                            sh.header().value().deleted(),
                            Fraction.ZERO));
        }
        var acc =
                new Accumulator(
                        state.potId().value(),
                        state.version(),
                        List.copyOf(sortedShareholders.values()));
        for (var expense : state.expenses().values()) {
            if (!expense.header().value().deleted()) {
                acc.applyExpense(expense.financials().value(), 1);
            }
        }
        return Optional.of(acc.build());
    }

    public PotBalanceAggregate addExpense(
            PotBalanceAggregate aggregate, ExpenseFinancials expense) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(expense, "expense");
        var acc = Accumulator.from(aggregate, aggregate.version());
        acc.applyExpense(expense, 1);
        return acc.build();
    }

    public PotBalanceAggregate removeExpense(
            PotBalanceAggregate aggregate, ExpenseFinancials expense) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(expense, "expense");
        var acc = Accumulator.from(aggregate, aggregate.version());
        acc.applyExpense(expense, -1);
        return acc.build();
    }

    /**
     * Composes a target version aggregate from a base aggregate by applying shareholder weight
     * updates/additions, removing old expense contributions, and adding new expense contributions.
     * Re-indexes canonical shareholder indices automatically when new shareholders are introduced.
     */
    public PotBalanceAggregate applyDelta(
            PotBalanceAggregate base,
            long targetVersion,
            Map<UUID, Weight> updatedOrAddedWeights,
            Collection<ExpenseFinancials> removedExpenses,
            Collection<ExpenseFinancials> addedExpenses) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(updatedOrAddedWeights, "updatedOrAddedWeights");
        Objects.requireNonNull(removedExpenses, "removedExpenses");
        Objects.requireNonNull(addedExpenses, "addedExpenses");
        if (targetVersion < 1) {
            throw new IllegalArgumentException("Positive target version required");
        }
        var acc = Accumulator.fromReindexed(base, targetVersion, updatedOrAddedWeights);
        for (var removed : removedExpenses) {
            acc.applyExpense(Objects.requireNonNull(removed, "removed expense"), -1);
        }
        for (var added : addedExpenses) {
            acc.applyExpense(Objects.requireNonNull(added, "added expense"), 1);
        }
        return acc.build();
    }

    public PotBalance calculate(PotBalanceAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        return calculate(aggregate, aggregate.defaultWeights());
    }

    /**
     * Evaluates exact {@link PotBalance} from all buckets of {@code aggregate} using the provided
     * current shareholder default weights (D3).
     */
    public PotBalance calculate(PotBalanceAggregate aggregate, Map<UUID, Weight> currentWeights) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(currentWeights, "currentWeights");
        int count = aggregate.shareholders().size();
        var balances = new Fraction[count];
        var weights = new Fraction[count];
        for (int i = 0; i < count; i++) {
            var entry = aggregate.shareholders().get(i);
            balances[i] = entry.fixedNet();
            Weight provided = currentWeights.get(entry.shareholderId());
            if (provided != null) {
                weights[i] = provided.value();
            }
        }
        for (var bucket : aggregate.buckets().entrySet()) {
            evaluateBucket(bucket.getKey(), bucket.getValue(), weights, balances);
        }
        var result = new LinkedHashMap<UUID, Fraction>(count * 2);
        for (int i = 0; i < count; i++) {
            result.put(aggregate.shareholders().get(i).shareholderId(), balances[i]);
        }
        return new PotBalance(aggregate.potId(), aggregate.version(), result);
    }

    private static void evaluateBucket(
            BucketKey key, BucketValue value, Fraction[] weights, Fraction[] balances) {
        Fraction totalWeight = key.specificWeightTotal();
        for (int idx : key.defaultParticipantIndices()) {
            Fraction w = weights[idx];
            if (w == null) {
                throw new IllegalArgumentException(
                        "Missing current default weight for participant index: " + idx);
            }
            totalWeight = totalWeight.add(w);
        }
        Fraction debitPerWeight = Fraction.ZERO.subtract(value.totalAmount().divide(totalWeight));
        for (int idx : key.defaultParticipantIndices()) {
            balances[idx] = balances[idx].add(debitPerWeight.multiply(weights[idx]));
        }
        for (var spec : value.specWeightedAmounts().entrySet()) {
            int idx = spec.getKey();
            balances[idx] = balances[idx].subtract(spec.getValue().divide(totalWeight));
        }
    }

    private static final class Accumulator {
        private final UUID potId;
        private final long version;
        private final List<ShareholderEntry> shareholders;
        private final Map<UUID, Integer> indexById;
        private final Fraction[] fixedNets;
        private final Map<BucketKey, MutableBucket> buckets;

        private Accumulator(UUID potId, long version, List<ShareholderEntry> shareholders) {
            this.potId = potId;
            this.version = version;
            this.shareholders = shareholders;
            this.indexById = new HashMap<>(shareholders.size() * 2);
            this.fixedNets = new Fraction[shareholders.size()];
            for (int i = 0; i < shareholders.size(); i++) {
                var entry = shareholders.get(i);
                indexById.put(entry.shareholderId(), i);
                fixedNets[i] = entry.fixedNet();
            }
            this.buckets = new TreeMap<>();
        }

        private static Accumulator from(PotBalanceAggregate aggregate, long version) {
            var acc = new Accumulator(aggregate.potId(), version, aggregate.shareholders());
            for (var entry : aggregate.buckets().entrySet()) {
                acc.buckets.put(entry.getKey(), MutableBucket.copyOf(entry.getValue()));
            }
            return acc;
        }

        private static Accumulator fromReindexed(
                PotBalanceAggregate base, long targetVersion, Map<UUID, Weight> updatedOrAddedWeights) {
            var merged = new TreeMap<UUID, ShareholderEntry>(SubobjectKey::compareUuid);
            for (var entry : base.shareholders()) {
                Weight updated = updatedOrAddedWeights.get(entry.shareholderId());
                Fraction weight = updated != null ? updated.value() : entry.defaultWeight();
                merged.put(
                        entry.shareholderId(),
                        new ShareholderEntry(
                                entry.shareholderId(), weight, entry.deleted(), entry.fixedNet()));
            }
            for (var entry : updatedOrAddedWeights.entrySet()) {
                UUID id = Objects.requireNonNull(entry.getKey(), "shareholder id");
                Weight weight = Objects.requireNonNull(entry.getValue(), "shareholder weight");
                merged.putIfAbsent(id, new ShareholderEntry(id, weight.value(), false, Fraction.ZERO));
            }
            var newShareholders = List.copyOf(merged.values());
            var acc = new Accumulator(base.potId(), targetVersion, newShareholders);

            int oldCount = base.shareholders().size();
            var indexMap = new int[oldCount];
            for (int i = 0; i < oldCount; i++) {
                indexMap[i] = acc.indexById.get(base.shareholders().get(i).shareholderId());
            }
            for (var entry : base.buckets().entrySet()) {
                BucketKey remappedKey = remapKey(entry.getKey(), indexMap);
                MutableBucket remappedVal = remapValue(entry.getValue(), indexMap);
                acc.buckets.put(remappedKey, remappedVal);
            }
            return acc;
        }

        private static BucketKey remapKey(BucketKey key, int[] indexMap) {
            var mapped = new ArrayList<Integer>(key.defaultParticipantIndices().size());
            for (int oldIdx : key.defaultParticipantIndices()) {
                mapped.add(indexMap[oldIdx]);
            }
            return new BucketKey(mapped, key.specificWeightTotal());
        }

        private static MutableBucket remapValue(BucketValue value, int[] indexMap) {
            var mappedSpecs = new TreeMap<Integer, Fraction>();
            for (var spec : value.specWeightedAmounts().entrySet()) {
                mappedSpecs.put(indexMap[spec.getKey()], spec.getValue());
            }
            return new MutableBucket(value.totalAmount(), mappedSpecs);
        }

        private void applyExpense(ExpenseFinancials expense, int sign) {
            if (!potId.equals(expense.potId().value())) {
                throw new IllegalArgumentException("Expense belongs to a different Pot");
            }
            int payerIdx = requireActiveIndex(expense.payerId().value());
            var defaultIndices = new ArrayList<Integer>();
            var specificWeights = new TreeMap<Integer, Fraction>();
            Fraction specificTotal = Fraction.ZERO;
            for (var entry : expense.participants().entrySet()) {
                int idx = requireActiveIndex(entry.getKey().value());
                if (entry.getValue().isPresent()) {
                    Fraction w = entry.getValue().get().value();
                    specificWeights.put(idx, w);
                    specificTotal = specificTotal.add(w);
                } else {
                    defaultIndices.add(idx);
                }
            }
            Fraction amount = expense.amount().value();
            if (amount.equals(Fraction.ZERO)) {
                return;
            }
            fixedNets[payerIdx] =
                    sign > 0 ? fixedNets[payerIdx].add(amount) : fixedNets[payerIdx].subtract(amount);
            if (defaultIndices.isEmpty()) {
                applyPureSpecific(amount, specificTotal, specificWeights, sign);
            } else {
                Collections.sort(defaultIndices);
                applyBucketed(
                        new BucketKey(defaultIndices, specificTotal),
                        amount,
                        specificWeights,
                        sign);
            }
        }

        private void applyPureSpecific(
                Fraction amount,
                Fraction specificTotal,
                Map<Integer, Fraction> specificWeights,
                int sign) {
            Fraction debitPerWeight = amount.divide(specificTotal);
            for (var entry : specificWeights.entrySet()) {
                int idx = entry.getKey();
                Fraction debit = debitPerWeight.multiply(entry.getValue());
                fixedNets[idx] =
                        sign > 0 ? fixedNets[idx].subtract(debit) : fixedNets[idx].add(debit);
            }
        }

        private void applyBucketed(
                BucketKey key,
                Fraction amount,
                Map<Integer, Fraction> specificWeights,
                int sign) {
            if (sign > 0) {
                var bucket =
                        buckets.computeIfAbsent(
                                key, ignored -> new MutableBucket(Fraction.ZERO, new TreeMap<>()));
                bucket.totalAmount = bucket.totalAmount.add(amount);
                for (var entry : specificWeights.entrySet()) {
                    Fraction weighted = amount.multiply(entry.getValue());
                    bucket.specWeightedAmounts.merge(entry.getKey(), weighted, Fraction::add);
                }
            } else {
                var bucket = buckets.get(key);
                if (bucket == null) {
                    throw new IllegalArgumentException(
                            "Cannot remove expense from absent bucket: " + key);
                }
                Fraction remaining = bucket.totalAmount.subtract(amount);
                if (remaining.compareTo(Fraction.ZERO) < 0) {
                    throw new IllegalArgumentException(
                            "Cannot remove expense exceeding bucket total amount");
                }
                bucket.totalAmount = remaining;
                for (var entry : specificWeights.entrySet()) {
                    int idx = entry.getKey();
                    Fraction existing = bucket.specWeightedAmounts.get(idx);
                    if (existing == null) {
                        throw new IllegalArgumentException(
                                "Cannot remove absent specific participant contribution: " + idx);
                    }
                    Fraction updated = existing.subtract(amount.multiply(entry.getValue()));
                    int cmp = updated.compareTo(Fraction.ZERO);
                    if (cmp < 0) {
                        throw new IllegalArgumentException(
                                "Cannot remove specific contribution exceeding bucket aggregate");
                    } else if (cmp == 0) {
                        bucket.specWeightedAmounts.remove(idx);
                    } else {
                        bucket.specWeightedAmounts.put(idx, updated);
                    }
                }
                if (remaining.equals(Fraction.ZERO)) {
                    if (!bucket.specWeightedAmounts.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Non-empty specific contributions remain on zero-amount bucket");
                    }
                    buckets.remove(key);
                }
            }
        }

        private int requireActiveIndex(UUID shareholderId) {
            Integer idx = indexById.get(shareholderId);
            if (idx == null) {
                throw new IllegalArgumentException("Unknown shareholder in aggregate: " + shareholderId);
            }
            if (shareholders.get(idx).deleted()) {
                throw new IllegalArgumentException("Deleted shareholder referenced by active expense: " + shareholderId);
            }
            return idx;
        }

        private PotBalanceAggregate build() {
            var finalShareholders = new ArrayList<ShareholderEntry>(shareholders.size());
            for (int i = 0; i < shareholders.size(); i++) {
                var s = shareholders.get(i);
                finalShareholders.add(
                        new ShareholderEntry(
                                s.shareholderId(), s.defaultWeight(), s.deleted(), fixedNets[i]));
            }
            var finalBuckets = new TreeMap<BucketKey, BucketValue>();
            for (var entry : buckets.entrySet()) {
                var mb = entry.getValue();
                finalBuckets.put(
                        entry.getKey(), new BucketValue(mb.totalAmount, mb.specWeightedAmounts));
            }
            return new PotBalanceAggregate(potId, version, finalShareholders, finalBuckets);
        }

        private static final class MutableBucket {
            private Fraction totalAmount;
            private final TreeMap<Integer, Fraction> specWeightedAmounts;

            private MutableBucket(Fraction totalAmount, TreeMap<Integer, Fraction> specWeightedAmounts) {
                this.totalAmount = totalAmount;
                this.specWeightedAmounts = specWeightedAmounts;
            }

            private static MutableBucket copyOf(BucketValue value) {
                return new MutableBucket(value.totalAmount(), new TreeMap<>(value.specWeightedAmounts()));
            }
        }
    }
}
