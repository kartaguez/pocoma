package com.kartaguez.pocoma.supra.polling.consumption;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupSubscriber;
import com.kartaguez.pocoma.supra.polling.consumption.wait.ConditionConsumptionWaiter;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WakeupWorkerBehaviorTest {

    @Test
    void conditionWaiterLatchesSignalBeforeAwait() throws Exception {
        var waiter = new ConditionConsumptionWaiter();
        // Signal arrives strictly BEFORE await()
        waiter.signal();
        long start = System.nanoTime();
        waiter.await(Duration.ofSeconds(10));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 200, "Latched signal must return immediately, took " + elapsedMs + "ms");
    }

    @Test
    void conditionWaiterSecondAwaitSleepsNormallyAfterLatchConsumed() throws Exception {
        var waiter = new ConditionConsumptionWaiter();
        waiter.signal();
        waiter.await(Duration.ofSeconds(5));
        long start = System.nanoTime();
        waiter.await(Duration.ofMillis(40));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 25, "Latch must be consumed after first await, took " + elapsedMs + "ms");
    }

    @Test
    void noopSubscriberReportsNoopState() {
        var observed = new AtomicReference<WakeupState>();
        WakeupSubscriber.NOOP.start(Set.of(), k -> {}, observed::set);
        assertEquals(WakeupState.NOOP, observed.get());
        assertEquals(WakeupState.NOOP, WakeupSubscriber.NOOP.state());
    }
}
