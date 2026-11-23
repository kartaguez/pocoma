package com.kartaguez.pocoma.contract.consumption.wakeup;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded, non-blocking wakeup publisher shared identically across all transports.
 *
 * <p>Uses a single 32-bit bitmask covering all 7 families x 4 partitions (28 bits), making
 * unbounded buffer growth structurally impossible and ensuring O(1) lock-free calls from
 * business/HTTP threads.
 */
public final class CoalescingWakeupPublisher implements WakeupPublisher {
    public enum Strategy {
        /** Immediate async dispatch (still deduplicated by bitmask if sender thread is busy). */
        IN_FLIGHT_DEDUP,
        /** Coalesces notifications over a short window (windowNanos) before flushing. */
        WINDOWED
    }

    @FunctionalInterface
    public interface TransportSender {
        void send(WakeupKey key);
    }

    private final TransportSender sender;
    private final Strategy strategy;
    private final long windowNanos;
    private final WakeupMetrics metrics;
    private final AtomicInteger pendingMask = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public CoalescingWakeupPublisher(
            TransportSender sender,
            Strategy strategy,
            long windowMillis,
            WakeupMetrics metrics) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.windowNanos = Math.max(0L, windowMillis) * 1_000_000L;
        this.metrics = metrics == null ? WakeupMetrics.NOOP : metrics;
        this.worker =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("v3-wakeup-pub")
                        .start(this::drainLoop);
    }

    @Override
    public void publish(ConsumableFamily family, int partition) {
        if (!running.get() || family == null) return;
        if (partition < 0 || partition >= WakeupKey.DEFAULT_PARTITION_COUNT) return;
        int bit = 1 << (family.ordinal() * WakeupKey.DEFAULT_PARTITION_COUNT + partition);
        int prev = pendingMask.getAndAccumulate(bit, (a, b) -> a | b);
        if ((prev & bit) != 0) {
            metrics.onCoalesced();
            return;
        }
        if (prev == 0) {
            LockSupport.unpark(worker);
        }
    }

    private void drainLoop() {
        while (running.get()) {
            int mask = pendingMask.get();
            if (mask == 0) {
                LockSupport.park();
                continue;
            }
            if (strategy == Strategy.WINDOWED && windowNanos > 0) {
                LockSupport.parkNanos(windowNanos);
            }
            int batch = pendingMask.getAndSet(0);
            while (batch != 0 && running.get()) {
                int bitIndex = Integer.numberOfTrailingZeros(batch);
                batch &= ~(1 << bitIndex);
                try {
                    sender.send(WakeupKey.fromBitIndex(bitIndex));
                    metrics.onPublished();
                } catch (Throwable ignored) {
                    metrics.onPublishError();
                }
            }
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            LockSupport.unpark(worker);
            try {
                worker.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
