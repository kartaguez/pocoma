package com.kartaguez.pocoma.engine.consumption.v3;

import java.time.Duration;
import java.util.Objects;

/** Immutable operation bounds; changing these never rewrites durable deadlines or quarantine. */
public record LifecycleSettings(
        int batchSize,
        Duration leaseDuration,
        Duration claimDuration,
        Duration renewalInterval,
        Duration lockTimeout,
        Duration statementTimeout,
        RetryPolicy retry) {
    public record RetryPolicy(int maxFailures, Duration delay) {
        public RetryPolicy {
            if (maxFailures < 1) throw new IllegalArgumentException("N must be positive");
            micros(delay, true);
        }
    }

    public LifecycleSettings {
        if (batchSize < 1) throw new IllegalArgumentException("batch must be positive");
        Objects.requireNonNull(retry);
        for (var d :
                new Duration[] {
                    leaseDuration, claimDuration, renewalInterval, lockTimeout, statementTimeout
                }) micros(d, false);
        if (renewalInterval.compareTo(leaseDuration) >= 0
                || renewalInterval.compareTo(claimDuration) >= 0
                || lockTimeout.compareTo(statementTimeout) > 0)
            throw new IllegalArgumentException("invalid renewal/timeout margin");
        if (lockTimeout.toMillis() < 1 || statementTimeout.toMillis() < 1)
            throw new IllegalArgumentException("SQL timeout must be at least one millisecond");
    }

    public static long micros(Duration duration, boolean zeroAllowed) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()
                || (!zeroAllowed && duration.isZero())
                || duration.getNano() % 1000 != 0)
            throw new IllegalArgumentException(
                    "exact nonnegative PostgreSQL microseconds required");
        try {
            long value =
                    Math.addExact(
                            Math.multiplyExact(duration.getSeconds(), 1_000_000L),
                            duration.getNano() / 1000);
            // PostgreSQL interval arithmetic and timestamptz must remain representable.
            if (value > 31_536_000_000_000L)
                throw new IllegalArgumentException("duration exceeds one year");
            return value;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("duration overflow", overflow);
        }
    }

    public static LifecycleSettings pilot() {
        return new LifecycleSettings(
                32,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                new RetryPolicy(3, Duration.ofSeconds(30)));
    }
}
