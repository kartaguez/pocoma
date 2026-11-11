package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure regression for observation semantics; no Spring or PostgreSQL is started. */
class V3WorkerAwaitTest {
    @Test
    void preservesSuccessfulObservationAndTimeout() throws Exception {
        var transientSuccess = new AtomicInteger();
        V3WorkerPostgresTest.await(
                () -> transientSuccess.incrementAndGet() == 1, Duration.ofSeconds(1));
        assertEquals(1, transientSuccess.get(), "a successful observation must not be reread");

        var delayedSuccess = new AtomicInteger();
        V3WorkerPostgresTest.await(
                () -> delayedSuccess.incrementAndGet() == 3, Duration.ofSeconds(1));
        assertEquals(3, delayedSuccess.get());
        assertThrows(
                AssertionError.class,
                () -> V3WorkerPostgresTest.await(() -> false, Duration.ZERO));
    }
}
