package com.kartaguez.pocoma.runtime.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure regression for observation semantics; no Spring or PostgreSQL is started. */
class WorkerAwaitTest {
    @Test
    void preservesSuccessfulObservationAndTimeout() throws Exception {
        var transientSuccess = new AtomicInteger();
        WorkerPostgresTest.await(
                () -> transientSuccess.incrementAndGet() == 1, Duration.ofSeconds(1));
        assertEquals(1, transientSuccess.get(), "a successful observation must not be reread");

        var delayedSuccess = new AtomicInteger();
        WorkerPostgresTest.await(
                () -> delayedSuccess.incrementAndGet() == 3, Duration.ofSeconds(1));
        assertEquals(3, delayedSuccess.get());
        assertThrows(
                AssertionError.class,
                () -> WorkerPostgresTest.await(() -> false, Duration.ZERO));
    }
}
