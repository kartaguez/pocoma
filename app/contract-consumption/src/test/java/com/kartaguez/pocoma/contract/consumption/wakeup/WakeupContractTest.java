package com.kartaguez.pocoma.contract.consumption.wakeup;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WakeupContractTest {

    @Test
    void subjectAndBitIndexRoundtrip() {
        for (var fam : ConsumableFamily.values()) {
            for (int p = 0; p < 4; p++) {
                var key = new WakeupKey(fam, p);
                assertEquals(key, WakeupKey.parseSubject(key.subject()).orElseThrow());
                assertEquals(key, WakeupKey.fromBitIndex(key.bitIndex()));
            }
        }
        assertTrue(WakeupKey.parseSubject("invalid.subject").isEmpty());
        assertTrue(WakeupKey.parseSubject("pocoma.v3.wakeup.COMMAND.9").isEmpty());
    }

    @Test
    void coalescingPublisherDeduplicatesBurstAndBoundsMemory() throws Exception {
        var sent = new AtomicInteger();
        var coalesced = new AtomicInteger();
        var published = new AtomicInteger();
        var gate = new CountDownLatch(1);
        var firstEntered = new CountDownLatch(1);
        var metrics =
                new WakeupMetrics() {
                    @Override
                    public void onPublished() {
                        published.incrementAndGet();
                    }

                    @Override
                    public void onCoalesced() {
                        coalesced.incrementAndGet();
                    }
                };
        try (var pub =
                new CoalescingWakeupPublisher(
                        key -> {
                            sent.incrementAndGet();
                            firstEntered.countDown();
                            try {
                                gate.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP,
                        0,
                        metrics)) {
            pub.publish(ConsumableFamily.COMMAND, 1);
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            // While first publish is in-flight, burst 5000 publishes on same key
            for (int i = 0; i < 5_000; i++) {
                pub.publish(ConsumableFamily.COMMAND, 1);
            }
            gate.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (published.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(coalesced.get() >= 4_998, "Expected heavy coalescing, got " + coalesced.get());
            assertTrue(sent.get() <= 3, "Expected <=3 transport sends, got " + sent.get());
        }
    }

    @Test
    void publisherTransportFailureNeverThrowsToCaller() throws Exception {
        var errors = new AtomicInteger();
        var received = new CountDownLatch(1);
        var metrics =
                new WakeupMetrics() {
                    @Override
                    public void onPublishError() {
                        errors.incrementAndGet();
                        received.countDown();
                    }
                };
        try (var pub =
                new CoalescingWakeupPublisher(
                        key -> {
                            throw new RuntimeException("broker down");
                        },
                        CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP,
                        0,
                        metrics)) {
            assertDoesNotThrow(() -> pub.publish(ConsumableFamily.POT_EVENT, 2));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(1, errors.get());
        }
    }

    @Test
    void windowedStrategyDeliversDistinctKeys() throws Exception {
        Set<WakeupKey> keys = ConcurrentHashMap.newKeySet();
        var done = new CountDownLatch(2);
        try (var pub =
                new CoalescingWakeupPublisher(
                        key -> {
                            if (keys.add(key)) done.countDown();
                        },
                        CoalescingWakeupPublisher.Strategy.WINDOWED,
                        2,
                        WakeupMetrics.NOOP)) {
            for (int i = 0; i < 50; i++) {
                pub.publish(ConsumableFamily.POT_EVENT, 0);
                pub.publish(ConsumableFamily.COMMAND_COMPLETION_EVENT, 3);
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(
                    Set.of(
                            new WakeupKey(ConsumableFamily.POT_EVENT, 0),
                            new WakeupKey(ConsumableFamily.COMMAND_COMPLETION_EVENT, 3)),
                    keys);
        }
    }

    @Test
    void blockedTransportNeverBlocksBusinessThreadAndBoundsMemory() throws Exception {
        var senderBlocked = new CountDownLatch(1);
        var releaseSender = new CountDownLatch(1);
        var coalesced = new AtomicInteger();
        var published = new AtomicInteger();
        var metrics =
                new WakeupMetrics() {
                    @Override
                    public void onPublished() {
                        published.incrementAndGet();
                    }

                    @Override
                    public void onCoalesced() {
                        coalesced.incrementAndGet();
                    }
                };
        try (var pub =
                new CoalescingWakeupPublisher(
                        key -> {
                            senderBlocked.countDown();
                            try {
                                releaseSender.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP,
                        0,
                        metrics)) {
            pub.publish(ConsumableFamily.COMMAND, 0);
            assertTrue(senderBlocked.await(2, TimeUnit.SECONDS));

            // While transport is completely hung, 50,000 publishes across families/partitions
            // must complete in O(1) without blocking the caller or growing buffers.
            long startNanos = System.nanoTime();
            for (int i = 0; i < 50_000; i++) {
                pub.publish(ConsumableFamily.COMMAND, i & 3);
                pub.publish(ConsumableFamily.POT_EVENT, i & 3);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(
                    elapsedMs < 500,
                    "Hung transport must never block business caller; 100k publishes took "
                            + elapsedMs
                            + "ms");
            assertTrue(
                    coalesced.get() >= 99_990,
                    "Bitmask must coalesce saturated bursts into 32-bit state; coalesced="
                            + coalesced.get());

            releaseSender.countDown();
        }
        assertTrue(published.get() >= 1);
    }
}
