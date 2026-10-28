package com.kartaguez.pocoma.engine.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class WorkerCapacityTest {
    final ConsumptionId a = new ConsumptionId(new UUID(0, 1)),
            b = new ConsumptionId(new UUID(0, 2));

    @Test
    void simultaneousLimits() throws Exception {
        var capacity = new WorkerCapacity(4, Map.of(a, 2, b, 3));
        var start = new CountDownLatch(1);
        var reserved = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        var worker = new AtomicInteger();
        var byA = new AtomicInteger();
        var byB = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                var id = i % 2 == 0 ? a : b;
                futures.add(
                        threads.submit(
                                () -> {
                                    start.await();
                                    var tickets = capacity.reserve(id, 2);
                                    var local = id.equals(a) ? byA : byB;
                                    assertTrue(
                                            local.addAndGet(tickets.size())
                                                    <= (id.equals(a) ? 2 : 3));
                                    assertTrue(worker.addAndGet(tickets.size()) <= 4);
                                    reserved.countDown();
                                    release.await();
                                    worker.addAndGet(-tickets.size());
                                    local.addAndGet(-tickets.size());
                                    tickets.forEach(WorkerCapacity.Ticket::release);
                                    return null;
                                }));
            }
            start.countDown();
            assertTrue(reserved.await(5, TimeUnit.SECONDS));
            assertEquals(4, worker.get());
            assertEquals(4, capacity.snapshot().worker());
            release.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(0, capacity.snapshot().worker());
    }

    @Test
    void partialAndDoubleRelease() throws Exception {
        var capacity = new WorkerCapacity(3, Map.of(a, 2, b, 3));
        var tickets = capacity.reserve(a, 5);
        assertEquals(2, tickets.size());
        assertEquals(1, capacity.reserve(b, 5).size());
        var wins = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++)
                futures.add(
                        threads.submit(
                                () -> {
                                    if (tickets.getFirst().release()) wins.incrementAndGet();
                                }));
            for (var f : futures) f.get();
        }
        assertEquals(1, wins.get());
        assertEquals(2, capacity.snapshot().worker());
        assertEquals(1, capacity.reserve(b, 5).size());
    }

    @Test
    void settingsRejectInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LifecycleSettings.RetryPolicy(0, java.time.Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> LifecycleSettings.micros(java.time.Duration.ofNanos(1), false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LifecycleSettings.micros(
                                java.time.Duration.ofSeconds(Long.MAX_VALUE), false));
        assertThrows(IllegalArgumentException.class, () -> new WorkerCapacity(0, Map.of(a, 1)));
        assertEquals(
                30_000_000L,
                LifecycleSettings.micros(LifecycleSettings.pilot().retry().delay(), true));
    }

    @Test
    void readOnlyRetentionAndObserverFailureCannotReleaseOrLeak() {
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        var capacity =
                new WorkerCapacity(
                        2,
                        Map.of(a, 2),
                        (id, phase, outcome, n, elapsed) -> {
                            seen.addAndGet((int) n);
                            throw new AssertionError("metric failure");
                        });
        var tickets = capacity.reserve(a, 2);
        assertEquals(2, capacity.snapshot().worker());
        assertTrue(tickets.getFirst().retained());
        assertEquals(2, seen.get());
        assertTrue(tickets.getFirst().release());
        assertFalse(tickets.getFirst().retained());
        assertFalse(tickets.getFirst().release());
        assertTrue(tickets.getLast().release());
        assertEquals(0, capacity.snapshot().worker());
        assertEquals(4, seen.get());
    }
}
