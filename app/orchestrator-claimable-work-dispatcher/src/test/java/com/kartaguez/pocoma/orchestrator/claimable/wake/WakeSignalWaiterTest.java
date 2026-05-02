package com.kartaguez.pocoma.orchestrator.claimable.wake;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class WakeSignalWaiterTest {

	@Test
	void wakesWhenSignalMatches() throws InterruptedException {
		InMemoryWorkWakeBus<String, Integer> wakeBus = new InMemoryWorkWakeBus<>();
		WakeSignalWaiter<String, Integer> waiter = new WakeSignalWaiter<>(
				wakeBus,
				Set.of("WORK_AVAILABLE"),
				key -> key == 1,
				Duration.ofSeconds(5),
				true);
		CountDownLatch awoken = new CountDownLatch(1);
		Thread waiterThread = new Thread(() -> {
			waiter.awaitWakeUp();
			awoken.countDown();
		});
		waiterThread.start();

		wakeBus.publish("WORK_AVAILABLE", 1);

		assertTrue(awoken.await(1, TimeUnit.SECONDS));
		waiter.close();
	}

	@Test
	void wakesOnTimeoutWhenSignalsAreDisabled() throws InterruptedException {
		WakeSignalWaiter<String, Integer> waiter = new WakeSignalWaiter<>(
				WorkWakeBus.noop(),
				Set.of("WORK_AVAILABLE"),
				key -> true,
				Duration.ofMillis(20),
				false);

		long startedAtNanos = System.nanoTime();
		waiter.awaitWakeUp();

		assertTrue(Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis() >= 10);
		waiter.close();
	}
}
