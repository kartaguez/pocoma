package com.kartaguez.pocoma.contract.consumption.invocation;

import java.util.*;

public interface ConsumptionNavigation {
    record Walk(Optional<UUID> afterExclusive, Optional<UUID> upperInclusive) {
        public Walk {
            Objects.requireNonNull(afterExclusive);
            Objects.requireNonNull(upperInclusive);
            if (afterExclusive.isPresent()
                    && (upperInclusive.isEmpty()
                            || compare(afterExclusive.get(), upperInclusive.get()) > 0))
                throw new IllegalArgumentException("invalid slot walk");
        }

        public static Walk fresh() {
            return new Walk(Optional.empty(), Optional.empty());
        }
    }
    record Page(Optional<UUID> upperInclusive, List<UUID> keys) {
        public Page {
            Objects.requireNonNull(upperInclusive);
            keys = List.copyOf(keys);
            UUID previous = null;
            for (var key : keys) {
                if (upperInclusive.isEmpty()
                        || compare(key, upperInclusive.get()) > 0
                        || (previous != null && compare(previous, key) >= 0))
                    throw new IllegalArgumentException("unordered slot page");
                previous = key;
            }
        }
    }
    static int compare(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
