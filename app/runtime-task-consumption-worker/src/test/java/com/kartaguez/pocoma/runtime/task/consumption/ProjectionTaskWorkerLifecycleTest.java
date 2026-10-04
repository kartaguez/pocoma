package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

class ProjectionTaskWorkerLifecycleTest {

	@Test
	void springShutdownCallbackWaitsForTheActiveCycle() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		var lifecycle = new ProjectionTaskWorkerLifecycle(worker(true, entered, release));

		lifecycle.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		assertTrue(lifecycle.isRunning());
		lifecycle.stop(stopped::countDown);

		assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
		release.countDown();
		assertTrue(stopped.await(2, TimeUnit.SECONDS));
		assertFalse(lifecycle.isRunning());
	}

	@Test
	void disabledWorkerCompletesSpringShutdownImmediately() throws Exception {
		CountDownLatch stopped = new CountDownLatch(1);
		var lifecycle = new ProjectionTaskWorkerLifecycle(
				worker(false, new CountDownLatch(1), new CountDownLatch(0)));

		lifecycle.start();
		lifecycle.stop(stopped::countDown);

		assertTrue(stopped.await(100, TimeUnit.MILLISECONDS));
		assertFalse(lifecycle.isRunning());
	}

	private static ConsumptionPollingWorker worker(boolean enabled, CountDownLatch entered, CountDownLatch release) {
		var settings = new ConsumptionWorkerSettings(enabled, new WorkerId("projection-task-lifecycle-test"),
				new ClaimLease(Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(100, 10),
				Duration.ofSeconds(1), Duration.ofSeconds(5));
		return new ConsumptionPollingWorker(input -> {
			entered.countDown();
			await(release);
			return new ConsumptionOrchestrationResult.Idle(
					Optional.empty(), new ConsumptionOrchestrationCounters(1, 1));
		}, settings, Clock.systemUTC(), new ConditionConsumptionWaiter());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("worker execution was interrupted", exception);
		}
	}
}
