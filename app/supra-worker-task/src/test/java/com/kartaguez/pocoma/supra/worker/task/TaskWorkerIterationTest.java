package com.kartaguez.pocoma.supra.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException;

class TaskWorkerIterationTest {
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);
	private static final WorkerId WORKER = new WorkerId("task-1");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final RecordedTask TASK = new RecordedTask(
			UUID.fromString("00000000-0000-0000-0000-000000000001"), PIPELINE,
			PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000010")), 2, NOW,
			"COMPUTE", "{}", Optional.empty());

	@Test
	void returnsIdleWithoutGuardMapperOrLifecycle() {
		Fixture fixture = fixture(input -> Optional.empty());
		assertFalse(fixture.iteration.runOnce());
		assertEquals(0, fixture.guardCalls.get());
		assertEquals(List.of(TaskWorkerRunOutcome.IDLE), fixture.outcomes());
	}

	@Test
	void executesMapsAndCompletesExactlyOneTask() {
		Fixture fixture = fixture(claiming());
		assertTrue(fixture.iteration.runOnce());
		assertEquals(1, fixture.guardCalls.get());
		assertEquals(1, fixture.mapperCalls.get());
		assertEquals(1, fixture.executions.get());
		assertEquals(1, fixture.completes.get());
		assertEquals(List.of(TaskWorkerRunOutcome.EXECUTED_AND_COMPLETED), fixture.outcomes());
	}

	@Test
	void alreadyExecutedSkipsMapperAndHandlerButCompletes() {
		Fixture fixture = fixture(claiming());
		fixture.guardOutcome = ExecutionOutcome.ALREADY_EXECUTED;
		assertTrue(fixture.iteration.runOnce());
		assertEquals(0, fixture.mapperCalls.get());
		assertEquals(0, fixture.executions.get());
		assertEquals(1, fixture.completes.get());
		assertEquals(List.of(TaskWorkerRunOutcome.ALREADY_EXECUTED_AND_COMPLETED), fixture.outcomes());
	}

	@Test
	void classifiedCallbackFailureFailsAfterGuardRollback() {
		Fixture fixture = fixture(claiming());
		fixture.mappingFailure = new RecordedTaskMappingException(
				RecordedTaskMappingException.INVALID_TASK_PAYLOAD, "invalid payload");
		fixture.classification = Optional.of(new ProcessingFailure("INVALID_TASK_PAYLOAD", "invalid payload", NOW));

		assertTrue(fixture.iteration.runOnce());
		assertEquals(1, fixture.failures.get());
		assertEquals(0, fixture.completes.get());
		assertEquals(0, fixture.releases.get());
	}

	@Test
	void unclassifiedCallbackAndGuardFailuresDoNotTransitionLifecycle() {
		Fixture callback = fixture(claiming());
		IllegalStateException callbackFailure = new IllegalStateException("handler unavailable");
		callback.executionFailure = callbackFailure;
		assertSame(callbackFailure, assertThrows(IllegalStateException.class, callback.iteration::runOnce));
		assertNoLifecycle(callback);

		Fixture guard = fixture(claiming());
		IllegalStateException guardFailure = new IllegalStateException("commit outcome unknown");
		guard.guardFailure = guardFailure;
		assertSame(guardFailure, assertThrows(IllegalStateException.class, guard.iteration::runOnce));
		assertNoLifecycle(guard);
	}

	@Test
	void stoppingBeforeGuardReleasesAndOwnershipLossNeverCompensates() {
		Fixture stopped = fixture(claiming());
		stopped.stopping.set(true);
		assertTrue(stopped.iteration.runOnce());
		assertEquals(1, stopped.releases.get());
		assertEquals(0, stopped.guardCalls.get());

		Fixture lost = fixture(claiming());
		lost.completionOutcome = ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
		assertTrue(lost.iteration.runOnce());
		assertEquals(0, lost.failures.get());
		assertEquals(0, lost.releases.get());
		assertTrue(lost.outcomes().contains(TaskWorkerRunOutcome.OWNERSHIP_LOST));
	}

	@Test
	void observesLeaseWarningAndExceededWithoutInterruptingExecution() {
		Fixture warning = fixture(claiming());
		warning.elapsedNanos.set(Duration.ofSeconds(24).toNanos());
		assertTrue(warning.iteration.runOnce());
		assertTrue(warning.outcomes().contains(TaskWorkerRunOutcome.LEASE_WARNING));

		Fixture exceeded = fixture(claiming());
		exceeded.elapsedNanos.set(Duration.ofSeconds(31).toNanos());
		assertTrue(exceeded.iteration.runOnce());
		assertTrue(exceeded.outcomes().contains(TaskWorkerRunOutcome.LEASE_EXCEEDED));
	}

	private static void assertNoLifecycle(Fixture fixture) {
		assertEquals(0, fixture.completes.get());
		assertEquals(0, fixture.failures.get());
		assertEquals(0, fixture.releases.get());
	}

	private static ClaimNextTaskUseCase claiming() {
		return input -> Optional.of(claim());
	}

	private static TaskClaimResult claim() {
		Claim claim = Claim.active(ClaimId.generate(), new ConsumptionKey("task", List.of(TASK.taskId().toString())),
				ClaimToken.generate(), WORKER, NOW, LEASE);
		return new TaskClaimResult(TASK, claim);
	}

	private static Fixture fixture(ClaimNextTaskUseCase claimNext) {
		return new Fixture(claimNext);
	}

	private record Payload(String value) implements TaskPayload { }

	private static final class Fixture {
		private final AtomicInteger guardCalls = new AtomicInteger();
		private final AtomicInteger mapperCalls = new AtomicInteger();
		private final AtomicInteger executions = new AtomicInteger();
		private final AtomicInteger completes = new AtomicInteger();
		private final AtomicInteger failures = new AtomicInteger();
		private final AtomicInteger releases = new AtomicInteger();
		private final AtomicBoolean stopping = new AtomicBoolean();
		private final AtomicLong elapsedNanos = new AtomicLong();
		private final AtomicInteger timeReads = new AtomicInteger();
		private final List<TaskWorkerRunObservation> observations = new ArrayList<>();
		private ExecutionOutcome guardOutcome = ExecutionOutcome.EXECUTED;
		private RuntimeException guardFailure;
		private RuntimeException mappingFailure;
		private RuntimeException executionFailure;
		private Optional<ProcessingFailure> classification = Optional.empty();
		private ConsumptionOutcome completionOutcome = ConsumptionOutcome.APPLIED;
		private final TaskWorkerIteration iteration;

		private Fixture(ClaimNextTaskUseCase claimNext) {
			ExecutionGuard<UUID> guard = (key, callback) -> {
				guardCalls.incrementAndGet();
				assertEquals(TASK.taskId(), key);
				if (guardFailure != null) throw guardFailure;
				if (guardOutcome == ExecutionOutcome.EXECUTED) callback.run();
				return guardOutcome;
			};
			RecordedTaskExecutionMapper<Payload> mapper = new RecordedTaskExecutionMapper<>() {
				@Override public PipelineDefinition pipeline() { return PIPELINE; }
				@Override public String taskType() { return TASK.taskType(); }
				@Override public Class<Payload> payloadType() { return Payload.class; }
				@Override public ExecuteTaskInput<Payload> map(RecordedTask task) {
					mapperCalls.incrementAndGet();
					if (mappingFailure != null) throw mappingFailure;
					return new ExecuteTaskInput<>(PIPELINE, TASK.taskType(), new Payload("mapped"));
				}
			};
			iteration = new TaskWorkerIteration(
					claimNext, guard, new RecordedTaskExecutionMapperRegistry(List.of(mapper)), input -> {
						executions.incrementAndGet();
						if (executionFailure != null) throw executionFailure;
					},
					input -> { completes.incrementAndGet(); return completionOutcome; },
					input -> { failures.incrementAndGet(); return ConsumptionOutcome.APPLIED; },
					input -> { releases.incrementAndGet(); return ConsumptionOutcome.APPLIED; },
					ignored -> classification,
					observations::add,
					() -> timeReads.getAndIncrement() == 0 ? 0L : elapsedNanos.get(),
					WORKER, LEASE, WorkerSegment.single(), PIPELINE, stopping::get);
		}

		private List<TaskWorkerRunOutcome> outcomes() {
			return observations.stream().map(TaskWorkerRunObservation::outcome).toList();
		}
	}
}
