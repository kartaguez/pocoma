package com.kartaguez.pocoma.engine.service.processing.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.NotAcquiredBusy;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.command.CommandPort;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.processing.command.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.security.UserContext;

class ConcurrentCommandProcessingTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));

	@Test
	void concurrentSegmentsClaimAGlobalCommandOnlyOnce() throws Exception {
		RecordedCommand command = command(1, NOW);
		AtomicAcquisition acquisition = new AtomicAcquisition();
		ClaimNextCommandService service = new ClaimNextCommandService(new Commands(command), acquisition);

		List<Optional<CommandClaimResult>> results = race(
				() -> service.claimNext(input("worker-a", new WorkerSegment(0, 2))),
				() -> service.claimNext(input("worker-b", new WorkerSegment(1, 2))));

		assertEquals(1, results.stream().filter(Optional::isPresent).count());
		assertEquals(1, acquisition.claimCount());
	}

	@Test
	void concurrentWorkersInOneSegmentCanClaimDifferentCommands() throws Exception {
		AtomicAcquisition acquisition = new AtomicAcquisition();
		ClaimNextCommandService service = new ClaimNextCommandService(
				new Commands(command(1, NOW), command(2, NOW.plusSeconds(1))), acquisition);

		List<Optional<CommandClaimResult>> results = race(
				() -> service.claimNext(input("worker-a", WorkerSegment.single())),
				() -> service.claimNext(input("worker-b", WorkerSegment.single())));

		assertEquals(2, results.stream().filter(Optional::isPresent).count());
		assertNotEquals(results.get(0).orElseThrow().command().commandId(),
				results.get(1).orElseThrow().command().commandId());
	}

	private static ClaimNextCommandInput input(String worker, WorkerSegment segment) {
		return new ClaimNextCommandInput(new WorkerId(worker), LEASE, segment);
	}

	private static RecordedCommand command(int id, Instant createdAt) {
		return new RecordedCommand(uuid(id), Optional.empty(), createdAt,
				new UserContext(UserId.of(uuid(99)), Set.of()), new CreatePotCommand("Trip", uuid(100 + id)));
	}

	private static CommandOrderingKey ordering(RecordedCommand command) {
		return new CommandOrderingKey(command.createdAt(), command.commandId());
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}

	private static <T> List<T> race(java.util.concurrent.Callable<T> first,
			java.util.concurrent.Callable<T> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<T> a = executor.submit(() -> { start.await(); return first.call(); });
			Future<T> b = executor.submit(() -> { start.await(); return second.call(); });
			start.countDown();
			return List.of(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private record Commands(List<RecordedCommand> values) implements CommandPort {
		private Commands(RecordedCommand... values) { this(List.of(values)); }

		@Override
		public Optional<RecordedCommand> findNextReady(
				WorkerSegment segment, Optional<CommandOrderingKey> afterExclusive) {
			return values.stream()
					.filter(value -> afterExclusive.map(cursor -> ordering(value).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(ConcurrentCommandProcessingTest::ordering));
		}

		@Override public void markCompleted(UUID commandId) { }
		@Override public void markFailed(UUID commandId, ProcessingFailure failure) { }
	}

	private static final class AtomicAcquisition implements TryAcquireConsumptionUseCase {
		private final java.util.Map<ConsumptionKey, Claim> claims = new java.util.HashMap<>();

		@Override
		public synchronized TryAcquireConsumptionResult tryAcquire(TryAcquireConsumptionInput input) {
			if (claims.containsKey(input.consumptionKey())) return new NotAcquiredBusy();
			Claim claim = Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease());
			claims.put(input.consumptionKey(), claim);
			return new Acquired(claim);
		}

		private synchronized int claimCount() { return claims.size(); }
	}
}
