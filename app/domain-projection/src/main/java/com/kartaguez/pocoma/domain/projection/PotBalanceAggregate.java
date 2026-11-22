package com.kartaguez.pocoma.domain.projection;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import java.util.*;

/**
 * Exact weight-independent aggregate representation of a Pot's active financial state at a global
 * version. Shareholders are stored in canonical UUID order, and bucket participant references use
 * their stable integer indices in that dictionary (D1).
 */
public record PotBalanceAggregate(
        UUID potId,
        long version,
        List<ShareholderEntry> shareholders,
        Map<BucketKey, BucketValue> buckets) {

    public record ShareholderEntry(
            UUID shareholderId, Fraction defaultWeight, boolean deleted, Fraction fixedNet) {
        public ShareholderEntry {
            Objects.requireNonNull(shareholderId, "shareholderId");
            Objects.requireNonNull(defaultWeight, "defaultWeight");
            Objects.requireNonNull(fixedNet, "fixedNet");
            if (defaultWeight.compareTo(Fraction.ZERO) <= 0) {
                throw new IllegalArgumentException("Shareholder default weight must be positive");
            }
        }
    }

    public record BucketKey(List<Integer> defaultParticipantIndices, Fraction specificWeightTotal)
            implements Comparable<BucketKey> {
        public BucketKey {
            Objects.requireNonNull(defaultParticipantIndices, "defaultParticipantIndices");
            Objects.requireNonNull(specificWeightTotal, "specificWeightTotal");
            if (defaultParticipantIndices.isEmpty()) {
                throw new IllegalArgumentException("Bucket requires at least one default participant");
            }
            if (specificWeightTotal.compareTo(Fraction.ZERO) < 0) {
                throw new IllegalArgumentException("Specific weight total must be non-negative");
            }
            var copy = List.copyOf(defaultParticipantIndices);
            int previous = -1;
            for (int index : copy) {
                if (index <= previous) {
                    throw new IllegalArgumentException(
                            "Default participant indices must be strictly ascending and non-negative");
                }
                previous = index;
            }
            defaultParticipantIndices = copy;
        }

        @Override
        public int compareTo(BucketKey other) {
            int limit = Math.min(defaultParticipantIndices.size(), other.defaultParticipantIndices.size());
            for (int i = 0; i < limit; i++) {
                int cmp =
                        Integer.compare(
                                defaultParticipantIndices.get(i),
                                other.defaultParticipantIndices.get(i));
                if (cmp != 0) return cmp;
            }
            int sizeCmp =
                    Integer.compare(
                            defaultParticipantIndices.size(),
                            other.defaultParticipantIndices.size());
            if (sizeCmp != 0) return sizeCmp;
            return specificWeightTotal.compareTo(other.specificWeightTotal);
        }
    }

    public record BucketValue(Fraction totalAmount, Map<Integer, Fraction> specWeightedAmounts) {
        public BucketValue {
            Objects.requireNonNull(totalAmount, "totalAmount");
            Objects.requireNonNull(specWeightedAmounts, "specWeightedAmounts");
            if (totalAmount.compareTo(Fraction.ZERO) <= 0) {
                throw new IllegalArgumentException("Bucket total amount must be positive");
            }
            var sorted = new TreeMap<Integer, Fraction>();
            for (var entry : specWeightedAmounts.entrySet()) {
                Integer idx = Objects.requireNonNull(entry.getKey(), "participant index");
                Fraction amount = Objects.requireNonNull(entry.getValue(), "spec weighted amount");
                if (idx < 0) {
                    throw new IllegalArgumentException("Negative participant index");
                }
                if (amount.compareTo(Fraction.ZERO) <= 0) {
                    throw new IllegalArgumentException("Specific weighted amount must be positive");
                }
                sorted.put(idx, amount);
            }
            specWeightedAmounts = Collections.unmodifiableMap(sorted);
        }
    }

    public PotBalanceAggregate {
        Objects.requireNonNull(potId, "potId");
        if (version < 1) {
            throw new IllegalArgumentException("Positive version required");
        }
        Objects.requireNonNull(shareholders, "shareholders");
        Objects.requireNonNull(buckets, "buckets");

        var canonicalShareholders = List.copyOf(shareholders);
        validateShareholderOrder(canonicalShareholders);

        var sortedBuckets = new TreeMap<BucketKey, BucketValue>();
        sortedBuckets.putAll(buckets);
        validateBucketsAndConservation(canonicalShareholders, sortedBuckets);

        shareholders = canonicalShareholders;
        buckets = Collections.unmodifiableMap(sortedBuckets);
    }

    private static void validateShareholderOrder(List<ShareholderEntry> entries) {
        UUID previous = null;
        for (var entry : entries) {
            Objects.requireNonNull(entry, "shareholder entry");
            if (previous != null && SubobjectKey.compareUuid(previous, entry.shareholderId()) >= 0) {
                throw new IllegalArgumentException(
                        "Shareholder dictionary must be strictly ordered by canonical UUID");
            }
            previous = entry.shareholderId();
        }
    }

    private static void validateBucketsAndConservation(
            List<ShareholderEntry> shareholders, Map<BucketKey, BucketValue> buckets) {
        int shareholderCount = shareholders.size();
        Fraction totalBucketAmounts = Fraction.ZERO;
        for (var entry : buckets.entrySet()) {
            BucketKey key = Objects.requireNonNull(entry.getKey(), "bucket key");
            BucketValue value = Objects.requireNonNull(entry.getValue(), "bucket value");
            validateBucket(key, value, shareholderCount);
            totalBucketAmounts = totalBucketAmounts.add(value.totalAmount());
        }
        Fraction totalFixedNet = Fraction.ZERO;
        for (var s : shareholders) {
            totalFixedNet = totalFixedNet.add(s.fixedNet());
        }
        if (!totalFixedNet.equals(totalBucketAmounts)) {
            throw new IllegalArgumentException(
                    "Aggregate conservation violated: sum(fixedNet)="
                            + totalFixedNet
                            + " != sum(bucketAmounts)="
                            + totalBucketAmounts);
        }
    }

    private static void validateBucket(BucketKey key, BucketValue value, int shareholderCount) {
        var defaultSet = new HashSet<Integer>(key.defaultParticipantIndices().size() * 2);
        for (int idx : key.defaultParticipantIndices()) {
            if (idx >= shareholderCount) {
                throw new IllegalArgumentException("Default participant index out of bounds: " + idx);
            }
            defaultSet.add(idx);
        }
        boolean hasSpecific = key.specificWeightTotal().compareTo(Fraction.ZERO) > 0;
        if (!hasSpecific && !value.specWeightedAmounts().isEmpty()) {
            throw new IllegalArgumentException(
                    "Pure default-weight bucket cannot carry specific weighted amounts");
        }
        if (hasSpecific && value.specWeightedAmounts().isEmpty()) {
            throw new IllegalArgumentException(
                    "Mixed bucket requires specific weighted amounts");
        }
        Fraction specSum = Fraction.ZERO;
        for (var specEntry : value.specWeightedAmounts().entrySet()) {
            int idx = specEntry.getKey();
            if (idx >= shareholderCount) {
                throw new IllegalArgumentException("Specific participant index out of bounds: " + idx);
            }
            if (defaultSet.contains(idx)) {
                throw new IllegalArgumentException(
                        "Participant index cannot be both default and specific in the same bucket: " + idx);
            }
            specSum = specSum.add(specEntry.getValue());
        }
        Fraction expectedSpecSum = value.totalAmount().multiply(key.specificWeightTotal());
        if (!specSum.equals(expectedSpecSum)) {
            throw new IllegalArgumentException(
                    "Bucket specific-weight conservation violated: sum(Q)="
                            + specSum
                            + " != T*W_spec="
                            + expectedSpecSum);
        }
    }

    public int indexOf(UUID shareholderId) {
        Objects.requireNonNull(shareholderId, "shareholderId");
        int low = 0;
        int high = shareholders.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = SubobjectKey.compareUuid(shareholders.get(mid).shareholderId(), shareholderId);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    public int bucketCount() {
        return buckets.size();
    }

    public int bucketEntryCount() {
        int total = 0;
        for (var entry : buckets.entrySet()) {
            total += entry.getKey().defaultParticipantIndices().size();
            total += entry.getValue().specWeightedAmounts().size();
        }
        return total;
    }

    public Map<UUID, Weight> defaultWeights() {
        var weights = new LinkedHashMap<UUID, Weight>(shareholders.size() * 2);
        for (var entry : shareholders) {
            weights.put(entry.shareholderId(), new Weight(entry.defaultWeight()));
        }
        return Collections.unmodifiableMap(weights);
    }
}
