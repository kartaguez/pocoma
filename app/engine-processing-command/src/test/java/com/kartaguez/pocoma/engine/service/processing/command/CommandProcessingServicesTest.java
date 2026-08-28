package com.kartaguez.pocoma.engine.service.processing.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.processing.command.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.CompleteCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.FailCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ReleaseCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.port.out.processing.command.CommandPort;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.security.UserContext;

class CommandProcessingServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerId WORKER = new WorkerId("worker-1");

	@Test
	void selectsByCreationTimestampThenIdWithoutAPotOrVersionDimension() {
		RecordedCommand oldest = global(3, NOW.minusSeconds(1));
		RecordedCommand firstTie = global(1, NOW);
		RecordedCommand secondTie = forPot(2, uuid(90), NOW);
		InMemoryCommandPort commands = new InMemoryCommandPort(secondTie, firstTie, oldest);

		assertEquals(oldest.commandId(), commands.findNextReady(WorkerSegment.single(), Optional.empty())
				.orElseThrow().commandId());
		assertEquals(firstTie.commandId(), commands.findNextReady(WorkerSegment.single(),
				Optional.of(ordering(oldest))).orElseThrow().commandId());
		assertEquals(secondTie.commandId(), commands.findNextReady(WorkerSegment.single(),
				Optional.of(ordering(firstTie))).orElseThrow().commandId());
	}

	@Test
	void appliesPotSegmentationAndKeepsCommandsWithoutPotVisibleEverywhere() {
		UUID potId = uuid(90);
		RecordedCommand global = global(1, NOW);
		RecordedCommand partitioned = forPot(2, potId, NOW.plusSeconds(1));
		InMemoryCommandPort commands = new InMemoryCommandPort(global, partitioned);
		int owner = Math.floorMod(PartitionHash.forPot(potId).value(), 4);

		for (int index = 0; index < 4; index++) {
			WorkerSegment segment = new WorkerSegment(index, 4);
			assertEquals(global.commandId(), commands.findNextReady(segment, Optional.empty())
					.orElseThrow().commandId());
			assertEquals(index == owner, commands.findNextReady(segment, Optional.of(ordering(global))).isPresent());
		}
	}

	@Test
	void onlyOneSegmentCanActuallyClaimTheSameGlobalCommand() {
		RecordedCommand global = global(1, NOW);
		InMemoryCommandPort commands = new InMemoryCommandPort(global);
		InMemoryConsumption consumption = new InMemoryConsumption();
		ClaimNextCommandService service = new ClaimNextCommandService(commands, consumption);

		assertTrue(service.claimNext(request(new WorkerSegment(0, 2))).isPresent());
		assertTrue(service.claimNext(request(new WorkerSegment(1, 2))).isEmpty());
	}

	@Test
	void loserContinuesToTheNextCandidateAndReturnsOnlyOneCommand() {
		RecordedCommand first = global(1, NOW);
		RecordedCommand second = global(2, NOW.plusSeconds(1));
		InMemoryCommandPort commands = new InMemoryCommandPort(second, first);
		InMemoryConsumption consumption = new InMemoryConsumption();
		consumption.tryAcquire(new TryAcquireConsumptionInput(
				CommandProcessingKeys.forCommand(first.commandId()), new WorkerId("other"), LEASE));

		CommandClaimResult result = new ClaimNextCommandService(commands, consumption)
				.claimNext(request(WorkerSegment.single())).orElseThrow();

		assertEquals(second.commandId(), result.command().commandId());
		assertEquals(CommandProcessingKeys.forCommand(second.commandId()), result.claim().consumptionKey());
	}

	@Test
	void expiredClaimIsReplacedWithANewToken() {
		RecordedCommand command = global(1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		ConsumptionKey key = CommandProcessingKeys.forCommand(command.commandId());
		Claim expired = Claim.active(ClaimId.generate(), key, ClaimToken.generate(), WORKER,
				NOW.minusSeconds(31), LEASE);
		consumption.claims.put(key, expired);

		Claim reclaimed = new ClaimNextCommandService(new InMemoryCommandPort(command), consumption)
				.claimNext(request(WorkerSegment.single())).orElseThrow().claim();

		assertNotEquals(expired.token(), reclaimed.token());
	}

	@Test
	void transitionsChangeTheCommandOnlyWhenClaimOwnershipIsApplied() {
		RecordedCommand completedCommand = global(1, NOW);
		RecordedCommand failedCommand = global(2, NOW.plusSeconds(1));
		RecordedCommand releasedCommand = global(3, NOW.plusSeconds(2));
		InMemoryCommandPort commands = new InMemoryCommandPort(
				completedCommand, failedCommand, releasedCommand);
		InMemoryConsumption consumption = new InMemoryConsumption();
		Claim completed = consumption.acquire(completedCommand.commandId());
		Claim failed = consumption.acquire(failedCommand.commandId());
		Claim released = consumption.acquire(releasedCommand.commandId());
		ProcessingFailure failure = new ProcessingFailure("business", "rejected", NOW);

		assertEquals(ConsumptionOutcome.APPLIED,
				new CompleteCommandProcessingService(commands, consumption).complete(
						new CompleteCommandProcessingInput(completedCommand.commandId(), completed.token())));
		assertEquals(ConsumptionOutcome.APPLIED,
				new FailCommandProcessingService(commands, consumption).fail(
						new FailCommandProcessingInput(failedCommand.commandId(), failed.token(), failure)));
		assertEquals(ConsumptionOutcome.APPLIED,
				new ReleaseCommandProcessingService(consumption).release(
						new ReleaseCommandProcessingInput(releasedCommand.commandId(), released.token())));

		assertEquals(ConsumptionStatus.COMPLETED, commands.status(completedCommand.commandId()));
		assertEquals(ConsumptionStatus.FAILED, commands.status(failedCommand.commandId()));
		assertEquals(failure, commands.failures.get(failedCommand.commandId()));
		assertEquals(ConsumptionStatus.READY, commands.status(releasedCommand.commandId()));

		ClaimToken stale = ClaimToken.generate();
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteCommandProcessingService(commands, consumption).complete(
						new CompleteCommandProcessingInput(releasedCommand.commandId(), stale)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new FailCommandProcessingService(commands, consumption).fail(
						new FailCommandProcessingInput(releasedCommand.commandId(), stale, failure)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new ReleaseCommandProcessingService(consumption).release(
						new ReleaseCommandProcessingInput(releasedCommand.commandId(), stale)));
		assertEquals(ConsumptionStatus.READY, commands.status(releasedCommand.commandId()));
	}

	private static ClaimNextCommandInput request(WorkerSegment segment) {
		return new ClaimNextCommandInput(WORKER, LEASE, segment);
	}

	private static CommandOrderingKey ordering(RecordedCommand command) {
		return new CommandOrderingKey(command.createdAt(), command.commandId());
	}

	private static RecordedCommand global(int id, Instant createdAt) {
		return new RecordedCommand(uuid(id), Optional.empty(), createdAt, actor(),
				new CreatePotCommand("Trip", uuid(100 + id)));
	}

	private static RecordedCommand forPot(int id, UUID potId, Instant createdAt) {
		return new RecordedCommand(uuid(id), Optional.of(potId), createdAt, actor(),
				new UpdatePotDetailsCommand(potId, "Trip", 999));
	}

	private static UserContext actor() {
		return new UserContext(UserId.of(uuid(999)), Set.of());
	}

	private static UUID uuid(int suffix) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
	}

	private static final class InMemoryCommandPort implements CommandPort {
		private final Map<UUID, RecordedCommand> commands = new HashMap<>();
		private final Map<UUID, ConsumptionStatus> statuses = new HashMap<>();
		private final Map<UUID, ProcessingFailure> failures = new HashMap<>();

		private InMemoryCommandPort(RecordedCommand... commands) {
			for (RecordedCommand command : commands) {
				this.commands.put(command.commandId(), command);
				this.statuses.put(command.commandId(), ConsumptionStatus.READY);
			}
		}

		@Override
		public Optional<RecordedCommand> findNextReady(
				WorkerSegment segment, Optional<CommandOrderingKey> afterExclusive) {
			return commands.values().stream()
					.filter(command -> status(command.commandId()) == ConsumptionStatus.READY)
					.filter(command -> command.potId()
							.map(potId -> segment.owns(PartitionHash.forPot(potId))).orElse(true))
					.filter(command -> afterExclusive
							.map(cursor -> ordering(command).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(CommandProcessingServicesTest::ordering));
		}

		@Override
		public void markCompleted(UUID commandId) {
			update(commandId, ConsumptionStatus.COMPLETED);
		}

		@Override
		public void markFailed(UUID commandId, ProcessingFailure failure) {
			failures.put(commandId, failure);
			update(commandId, ConsumptionStatus.FAILED);
		}

		private void update(UUID commandId, ConsumptionStatus status) {
			statuses.put(commandId, status);
		}

		private ConsumptionStatus status(UUID commandId) {
			return statuses.get(commandId);
		}
	}

	private static final class InMemoryConsumption implements
			TryAcquireConsumptionUseCase,
			CompleteConsumptionUseCase,
			FailConsumptionUseCase,
			ReleaseConsumptionUseCase {

		private final Map<ConsumptionKey, Claim> claims = new HashMap<>();

		@Override
		public synchronized Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
			Claim current = claims.get(input.consumptionKey());
			if (current != null && current.isActiveAt(NOW)) {
				return Optional.empty();
			}
			Claim acquired = Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease());
			claims.put(input.consumptionKey(), acquired);
			return Optional.of(acquired);
		}

		@Override
		public synchronized ConsumptionOutcome complete(CompleteConsumptionInput input) {
			return mutate(input.consumptionKey(), input.claimToken());
		}

		@Override
		public synchronized ConsumptionOutcome fail(FailConsumptionInput input) {
			return mutate(input.consumptionKey(), input.claimToken());
		}

		@Override
		public synchronized ConsumptionOutcome release(ReleaseConsumptionInput input) {
			return mutate(input.consumptionKey(), input.claimToken());
		}

		private ConsumptionOutcome mutate(ConsumptionKey key, ClaimToken token) {
			Claim claim = claims.get(key);
			if (claim == null || !claim.isOwnedBy(token, NOW)) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			claims.put(key, claim.endAt(NOW));
			return ConsumptionOutcome.APPLIED;
		}

		private Claim acquire(UUID commandId) {
			return tryAcquire(new TryAcquireConsumptionInput(
					CommandProcessingKeys.forCommand(commandId), WORKER, LEASE)).orElseThrow();
		}
	}
}
