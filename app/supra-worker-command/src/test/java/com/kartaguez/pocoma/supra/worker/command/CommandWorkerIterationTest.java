package com.kartaguez.pocoma.supra.worker.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.usecase.ExecuteCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.security.UserContext;

class CommandWorkerIterationTest {

	private static final Instant NOW = Instant.parse("2026-08-29T07:00:00Z");
	private static final WorkerId WORKER_ID = new WorkerId("command-worker");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerSegment SEGMENT = WorkerSegment.single();

	@Test
	void idleDoesNotInvokeGuardOrLifecycle() {
		Fixture fixture = new Fixture(Optional.empty());

		assertFalse(fixture.iteration().runOnce());
		assertEquals(0, fixture.guardedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.IDLE), fixture.outcomes());
	}

	@Test
	void executesAndCompletesExactlyOneClaimedCommand() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));

		assertTrue(fixture.iteration().runOnce());
		assertEquals(1, fixture.executedCalls.get());
		assertEquals(1, fixture.completedCalls.get());
		assertEquals(0, fixture.failedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.EXECUTED_AND_COMPLETED), fixture.outcomes());
	}

	@Test
	void alreadyExecutedSkipsBusinessAndCompletesCurrentClaim() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));
		fixture.executionOutcome = ExecutionOutcome.ALREADY_EXECUTED;

		assertTrue(fixture.iteration().runOnce());
		assertEquals(0, fixture.executedCalls.get());
		assertEquals(1, fixture.completedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.ALREADY_EXECUTED_AND_COMPLETED), fixture.outcomes());
	}

	@Test
	void functionalFailureIsMappedAndFailedWithoutCompletion() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));
		var expected = new IllegalArgumentException("invalid\ncommand");
		fixture.executionFailure = expected;

		assertTrue(fixture.iteration().runOnce());
		assertEquals(1, fixture.failedCalls.get());
		assertEquals(0, fixture.completedCalls.get());
		assertEquals(new ProcessingFailureCode("ILLEGAL_ARGUMENT_EXCEPTION"), fixture.failure.code());
		assertEquals("COMMAND_EXECUTION_FAILURE", fixture.failure.category());
		assertEquals("invalid command", fixture.failure.message());
		assertEquals(NOW, fixture.failure.occurredAt());
	}

	@Test
	void technicalGuardFailureLeavesLifecycleUntouched() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));
		var expected = new IllegalStateException("commit outcome unknown");
		fixture.guardFailure = expected;

		assertSame(expected, assertThrows(IllegalStateException.class, () -> fixture.iteration().runOnce()));
		assertEquals(0, fixture.completedCalls.get());
		assertEquals(0, fixture.failedCalls.get());
		assertEquals(0, fixture.releasedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.TECHNICAL_ERROR), fixture.outcomes());
	}

	@Test
	void stopAfterClaimReleasesWithoutExecuting() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));
		fixture.stopping.set(true);

		assertTrue(fixture.iteration().runOnce());
		assertEquals(1, fixture.releasedCalls.get());
		assertEquals(0, fixture.guardedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.RELEASED_BEFORE_EXECUTION), fixture.outcomes());
	}

	@Test
	void staleCompletionIsObservedWithoutCompensation() {
		Fixture fixture = new Fixture(Optional.of(claimedCommand()));
		fixture.completeOutcome = ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;

		assertTrue(fixture.iteration().runOnce());
		assertEquals(0, fixture.failedCalls.get());
		assertEquals(0, fixture.releasedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.OWNERSHIP_LOST), fixture.outcomes());
	}

	@Test
	void recordsWarningAndExceededLeaseWithoutInterruptingExecution() {
		Fixture warning = new Fixture(Optional.of(claimedCommand()));
		warning.nanos.set(Duration.ofSeconds(25).toNanos());
		assertTrue(warning.iteration().runOnce());
		assertEquals(List.of(
				CommandWorkerRunOutcome.EXECUTED_AND_COMPLETED,
				CommandWorkerRunOutcome.LEASE_WARNING), warning.outcomes());

		Fixture exceeded = new Fixture(Optional.of(claimedCommand()));
		exceeded.nanos.set(Duration.ofSeconds(31).toNanos());
		assertTrue(exceeded.iteration().runOnce());
		assertEquals(1, exceeded.executedCalls.get());
		assertEquals(List.of(
				CommandWorkerRunOutcome.EXECUTED_AND_COMPLETED,
				CommandWorkerRunOutcome.LEASE_EXCEEDED), exceeded.outcomes());
	}

	@Test
	void committedEffectIsNotRepeatedAfterCompletionFailureAndReclaim() {
		AtomicBoolean journal = new AtomicBoolean();
		AtomicInteger effects = new AtomicInteger();
		ExecutionGuard<UUID> sharedGuard = (key, callback) -> {
			if (!journal.compareAndSet(false, true)) {
				return ExecutionOutcome.ALREADY_EXECUTED;
			}
			callback.run();
			effects.incrementAndGet();
			return ExecutionOutcome.EXECUTED;
		};
		Fixture firstOwner = new Fixture(Optional.of(claimedCommand()));
		firstOwner.customGuard = sharedGuard;
		firstOwner.completeFailure = new IllegalStateException("completion unavailable");
		assertThrows(IllegalStateException.class, () -> firstOwner.iteration().runOnce());

		Fixture nextOwner = new Fixture(Optional.of(claimedCommand()));
		nextOwner.customGuard = sharedGuard;
		assertTrue(nextOwner.iteration().runOnce());

		assertEquals(1, effects.get());
		assertEquals(0, nextOwner.executedCalls.get());
		assertEquals(1, nextOwner.completedCalls.get());
		assertEquals(List.of(CommandWorkerRunOutcome.ALREADY_EXECUTED_AND_COMPLETED), nextOwner.outcomes());
	}

	private static CommandClaimResult claimedCommand() {
		UUID commandId = UUID.fromString("10000000-0000-0000-0000-000000000001");
		RecordedCommand command = new RecordedCommand(
				commandId,
				Optional.empty(),
				NOW,
				new UserContext(UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000001")), Set.of()),
				new CreatePotCommand("Trip", UUID.fromString("30000000-0000-0000-0000-000000000001")));
		Claim claim = Claim.active(
				new ClaimId(UUID.fromString("40000000-0000-0000-0000-000000000001")),
				new ConsumptionKey("command", List.of(commandId.toString())),
				new ClaimToken(UUID.fromString("50000000-0000-0000-0000-000000000001")),
				WORKER_ID,
				NOW,
				LEASE);
		return new CommandClaimResult(command, claim);
	}

	private static final class Fixture {

		private final Optional<CommandClaimResult> claimed;
		private final AtomicBoolean stopping = new AtomicBoolean();
		private final AtomicLong nanos = new AtomicLong();
		private final AtomicInteger nanoReads = new AtomicInteger();
		private final AtomicInteger guardedCalls = new AtomicInteger();
		private final AtomicInteger executedCalls = new AtomicInteger();
		private final AtomicInteger completedCalls = new AtomicInteger();
		private final AtomicInteger failedCalls = new AtomicInteger();
		private final AtomicInteger releasedCalls = new AtomicInteger();
		private final List<CommandWorkerRunObservation> observations = new ArrayList<>();
		private ExecutionOutcome executionOutcome = ExecutionOutcome.EXECUTED;
		private ConsumptionOutcome completeOutcome = ConsumptionOutcome.APPLIED;
		private RuntimeException executionFailure;
		private RuntimeException guardFailure;
		private RuntimeException completeFailure;
		private ExecutionGuard<UUID> customGuard;
		private com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure failure;

		private Fixture(Optional<CommandClaimResult> claimed) {
			this.claimed = claimed;
		}

		private CommandWorkerIteration iteration() {
			ExecutionGuard<UUID> defaultGuard = (key, execution) -> {
				guardedCalls.incrementAndGet();
				if (guardFailure != null) {
					throw guardFailure;
				}
				if (executionOutcome == ExecutionOutcome.EXECUTED) {
					execution.run();
				}
				return executionOutcome;
			};
			ExecutionGuard<UUID> guard = customGuard == null ? defaultGuard : customGuard;
			ExecuteCommandUseCase execute = input -> {
				executedCalls.incrementAndGet();
				if (executionFailure != null) {
					throw executionFailure;
				}
			};
			return new CommandWorkerIteration(
					input -> claimed,
					guard,
					execute,
					input -> {
						completedCalls.incrementAndGet();
						if (completeFailure != null) {
							throw completeFailure;
						}
						return completeOutcome;
					},
					input -> { failedCalls.incrementAndGet(); failure = input.failure(); return ConsumptionOutcome.APPLIED; },
					input -> { releasedCalls.incrementAndGet(); return ConsumptionOutcome.APPLIED; },
					new CommandProcessingFailureMapper(Clock.fixed(NOW, ZoneOffset.UTC)),
					observations::add,
					() -> nanoReads.getAndIncrement() == 0 ? 0L : nanos.get(),
					WORKER_ID,
					LEASE,
					SEGMENT,
					stopping::get);
		}

		private List<CommandWorkerRunOutcome> outcomes() {
			return observations.stream().map(CommandWorkerRunObservation::outcome).toList();
		}
	}
}
