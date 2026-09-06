package com.kartaguez.pocoma.supra.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;
import com.kartaguez.pocoma.supra.consumption.wait.ConsumptionWaiter;

class ConsumptionPollingWorkerTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
	private final ConsumptionOrchestrationCounters counters = new ConsumptionOrchestrationCounters(0, 0);

	@Test
	void idleDelayUsesTheEarlierEligibility() {
		var worker = worker(Duration.ofSeconds(30));
		assertEquals(Duration.ofSeconds(7), worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.of(NOW.plusSeconds(7)), counters)));
		assertEquals(Duration.ZERO, worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.of(NOW), counters)));
		assertEquals(Duration.ofSeconds(30), worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters)));
	}

	@Test
	void budgetHasNoDelayAndRuntimeFailureUsesBackoff() {
		var worker = worker(Duration.ofSeconds(30));
		assertEquals(Duration.ZERO, worker.delay(new ConsumptionOrchestrationResult.BudgetExhausted(
				ConsumptionBudgetLimit.EXECUTIONS,
				Optional.empty(), counters)));
		assertEquals(Duration.ofSeconds(5), worker.delay(new ConsumptionOrchestrationResult.RuntimeFailure(
				new IllegalStateException(), Optional.empty(), counters)));
	}

	@Test
	void stopWakesIdleWaitAndCompletesWithoutStartingAnotherCycle() throws Exception {
		AtomicInteger cycles = new AtomicInteger();
		CountDownLatch callback = new CountDownLatch(1);
		LatchWaiter waiter = new LatchWaiter();
		var worker = runningWorker(input -> {
			cycles.incrementAndGet();
			return new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters);
		}, waiter, ConsumptionPollingWorkerObservation.noop());

		worker.start();
		assertTrue(waiter.awaiting.await(2, TimeUnit.SECONDS));
		worker.requestStop(callback::countDown);

		assertTrue(callback.await(2, TimeUnit.SECONDS));
		assertFalse(worker.isRunning());
		assertEquals(1, cycles.get());
		assertEquals(1, waiter.signals.get());
	}

	@Test
	void stopLetsAnActiveCycleFinishWithoutInterruptingIt() throws Exception {
		AtomicInteger cycles = new AtomicInteger();
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicInteger callbacks = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		var worker = runningWorker(input -> {
			cycles.incrementAndGet();
			entered.countDown();
			await(release);
			interrupted.set(Thread.currentThread().isInterrupted());
			return new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters);
		}, new LatchWaiter(), ConsumptionPollingWorkerObservation.noop());

		worker.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		worker.requestStop(() -> {
			callbacks.incrementAndGet();
			stopped.countDown();
		});
		assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
		assertTrue(worker.isRunning());
		release.countDown();

		assertTrue(stopped.await(2, TimeUnit.SECONDS));
		assertFalse(interrupted.get());
		assertFalse(worker.isRunning());
		assertEquals(1, cycles.get());
		assertEquals(1, callbacks.get());
	}

	@Test
	void reportsOnlyPollingLifecycleAndCompletedCycles() throws Exception {
		RecordingObservation observation = new RecordingObservation();
		LatchWaiter waiter = new LatchWaiter();
		var result = new ConsumptionOrchestrationResult.Idle(Optional.empty(),
				new ConsumptionOrchestrationCounters(3, 2));
		var worker = runningWorker(input -> result, waiter, observation);

		worker.start();
		assertTrue(waiter.awaiting.await(2, TimeUnit.SECONDS));
		CountDownLatch stopped = new CountDownLatch(1);
		worker.requestStop(stopped::countDown);

		assertTrue(stopped.await(2, TimeUnit.SECONDS));
		assertEquals(1, observation.started.get());
		assertEquals(1, observation.cycles.get());
		assertEquals(1, observation.stopped.get());
		assertEquals(result, observation.result);
		assertEquals(Duration.ofSeconds(30), observation.delay);
		assertEquals(Duration.ZERO, observation.duration);
	}

	@Test
	void stoppingAnAlreadyStoppedWorkerInvokesTheCallbackImmediately() {
		AtomicInteger callbacks = new AtomicInteger();
		var worker = worker(Duration.ofSeconds(30));
		worker.requestStop(callbacks::incrementAndGet);
		assertEquals(1, callbacks.get());
	}

	private ConsumptionPollingWorker worker(Duration polling) {
		var settings = new ConsumptionWorkerSettings(false, new WorkerId("worker"),
				new ClaimLease(Duration.ofSeconds(20)), new ConsumptionOrchestrationBudget(10, 2), polling,
				Duration.ofSeconds(5));
		return new ConsumptionPollingWorker(input -> new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters),
				settings, Clock.fixed(NOW, ZoneOffset.UTC), new ConditionConsumptionWaiter());
	}

	private ConsumptionPollingWorker runningWorker(
			com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator orchestrator,
			ConsumptionWaiter waiter,
			ConsumptionPollingWorkerObservation observation) {
		var settings = new ConsumptionWorkerSettings(true, new WorkerId("worker"),
				new ClaimLease(Duration.ofSeconds(20)), new ConsumptionOrchestrationBudget(10, 2),
				Duration.ofSeconds(30), Duration.ofSeconds(5));
		return new ConsumptionPollingWorker(orchestrator, settings, Clock.fixed(NOW, ZoneOffset.UTC), waiter,
				observation);
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

	private static final class LatchWaiter implements ConsumptionWaiter {
		private final CountDownLatch awaiting = new CountDownLatch(1);
		private final CountDownLatch signalled = new CountDownLatch(1);
		private final AtomicInteger signals = new AtomicInteger();
		@Override public void await(Duration duration) throws InterruptedException {
			awaiting.countDown();
			signalled.await(2, TimeUnit.SECONDS);
		}
		@Override public void signal() {
			signals.incrementAndGet();
			signalled.countDown();
		}
	}

	private static final class RecordingObservation implements ConsumptionPollingWorkerObservation {
		private final AtomicInteger started = new AtomicInteger();
		private final AtomicInteger cycles = new AtomicInteger();
		private final AtomicInteger stopped = new AtomicInteger();
		private ConsumptionOrchestrationResult result;
		private Duration duration;
		private Duration delay;
		@Override public void workerStarted() { started.incrementAndGet(); }
		@Override public void cycleCompleted(ConsumptionOrchestrationResult result, Duration duration,
				Duration selectedDelay) {
			this.result = result;
			this.duration = duration;
			this.delay = selectedDelay;
			cycles.incrementAndGet();
		}
		@Override public void workerStopped() { stopped.incrementAndGet(); }
	}
}
