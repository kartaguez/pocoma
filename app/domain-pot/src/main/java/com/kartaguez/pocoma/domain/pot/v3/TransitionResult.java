package com.kartaguez.pocoma.domain.pot.v3;

import java.util.Objects;

public sealed interface TransitionResult<T> {
    enum Rejection { FORBIDDEN, DELETED, NOT_FOUND, ALREADY_EXISTS, OBSOLETE, INVALID_REFERENCE, INVALID_ATTACHMENT, REFERENCED }
    record Rejected<T>(Rejection reason) implements TransitionResult<T> {
        public Rejected { Objects.requireNonNull(reason); }
    }
    record Unchanged<T>(long currentPotVersion) implements TransitionResult<T> {
        public Unchanged { if (currentPotVersion < 1) throw new IllegalArgumentException("Invalid current version"); }
    }
    /** Only domain transitions construct effective proposals. A proposal is never a commit receipt. */
    final class Changed<T> implements TransitionResult<T> {
        private final long expectedPotVersion;
        private final long candidatePotVersion;
        private final T changes;
        private Changed(long expected, T changes) {
            if (expected < 0) throw new IllegalArgumentException("Invalid expected version");
            this.expectedPotVersion = expected;
            this.candidatePotVersion = Math.incrementExact(expected);
            this.changes = Objects.requireNonNull(changes);
        }
        static <T> Changed<T> propose(long expected, T changes) { return new Changed<>(expected, changes); }
        public long expectedPotVersion() { return expectedPotVersion; }
        public long candidatePotVersion() { return candidatePotVersion; }
        public T changes() { return changes; }
    }
}
