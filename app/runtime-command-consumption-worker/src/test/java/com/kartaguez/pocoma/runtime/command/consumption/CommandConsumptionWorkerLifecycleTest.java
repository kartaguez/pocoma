package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

class CommandConsumptionWorkerLifecycleTest {

	@Test
	void springCallbackShutdownReturnsWithoutInterruptingTheActiveCycle() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		var worker = worker(true, input -> {
			entered.countDown();
			try {
				release.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException exception) {
				interrupted.set(true);
				Thread.currentThread().interrupt();
			}
			return new ConsumptionOrchestrationResult.Idle(
					Optional.empty(), new ConsumptionOrchestrationCounters(1, 1));
		});
		var lifecycle = new CommandConsumptionWorkerLifecycle(worker);

		lifecycle.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		lifecycle.stop(stopped::countDown);

		assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
		release.countDown();
		assertTrue(stopped.await(2, TimeUnit.SECONDS));
		assertFalse(interrupted.get());
		assertFalse(lifecycle.isRunning());
	}

	@Test
	void disabledWorkerCompletesSpringShutdownImmediately() throws Exception {
		var worker = worker(false, input -> {
			throw new AssertionError("disabled worker must not run");
		});
		var lifecycle = new CommandConsumptionWorkerLifecycle(worker);
		CountDownLatch stopped = new CountDownLatch(1);

		lifecycle.start();
		lifecycle.stop(stopped::countDown);

		assertTrue(stopped.await(100, TimeUnit.MILLISECONDS));
		assertFalse(lifecycle.isRunning());
	}

	private static ConsumptionPollingWorker worker(boolean enabled,
			com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator orchestrator) {
		var settings = new ConsumptionWorkerSettings(enabled, new WorkerId("command-lifecycle-test"),
				new ClaimLease(Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(100, 10),
				Duration.ofSeconds(1), Duration.ofSeconds(5));
		return new ConsumptionPollingWorker(
				orchestrator, settings, Clock.systemUTC(), new ConditionConsumptionWaiter());
	}
}
