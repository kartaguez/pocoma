package com.kartaguez.pocoma.engine.service.processing.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyCompleted;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyFailed;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.NotAcquiredBusy;
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

		assertEquals(ConsumptionStatus.DONE, commands.status(completedCommand.commandId()));
		assertEquals(ConsumptionStatus.DONE, commands.status(failedCommand.commandId()));
		assertEquals(failure, commands.failures.get(failedCommand.commandId()));
		assertEquals(ConsumptionStatus.PENDING, commands.status(releasedCommand.commandId()));

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
		assertEquals(ConsumptionStatus.PENDING, commands.status(releasedCommand.commandId()));
	}

	@Test
	void reconcilesTerminalCommandsAndContinuesToTheNextClaimableCandidate() {
		RecordedCommand completed = global(1, NOW);
		RecordedCommand failed = global(2, NOW.plusSeconds(1));
		RecordedCommand claimable = global(3, NOW.plusSeconds(2));
		ProcessingFailure failure = new ProcessingFailure("business", "rejected", NOW);
		InMemoryCommandPort commands = new InMemoryCommandPort(completed, failed, claimable);
		TryAcquireConsumptionUseCase consumption = input -> {
			if (input.consumptionKey().equals(CommandProcessingKeys.forCommand(completed.commandId()))) {
				return new AlreadyCompleted();
			}
			if (input.consumptionKey().equals(CommandProcessingKeys.forCommand(failed.commandId()))) {
				return new AlreadyFailed(failure);
			}
			return new Acquired(Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease()));
		};

		CommandClaimResult result = new ClaimNextCommandService(commands, consumption)
				.claimNext(request(WorkerSegment.single())).orElseThrow();

		assertEquals(ConsumptionStatus.DONE, commands.status(completed.commandId()));
		assertEquals(ConsumptionStatus.DONE, commands.status(failed.commandId()));
		assertEquals(failure, commands.failures.get(failed.commandId()));
		assertEquals(claimable.commandId(), result.command().commandId());
	}

	@Test
	void terminalSlotSurvivesAMaterializationFailureAndIsReconciledLater() {
		RecordedCommand command = global(1, NOW);
		InMemoryCommandPort durable = new InMemoryCommandPort(command);
		List<String> calls = new ArrayList<>();
		CommandPort failingMaterialization = new CommandPort() {
			@Override public Optional<RecordedCommand> findNextReady(
					WorkerSegment segment, Optional<CommandOrderingKey> cursor) {
				return durable.findNextReady(segment, cursor);
			}
			@Override public void markCompleted(UUID commandId) {
				calls.add("mark");
				throw new IllegalStateException("storage unavailable");
			}
			@Override public void markFailed(UUID commandId, ProcessingFailure failure) {
				throw new UnsupportedOperationException();
			}
		};
		CompleteConsumptionUseCase lifecycle = input -> {
			calls.add("slot");
			return ConsumptionOutcome.APPLIED;
		};

		assertThrows(IllegalStateException.class, () -> new CompleteCommandProcessingService(
				failingMaterialization, lifecycle).complete(
						new CompleteCommandProcessingInput(command.commandId(), ClaimToken.generate())));
		assertEquals(List.of("slot", "mark"), calls);
		assertEquals(ConsumptionStatus.PENDING, durable.status(command.commandId()));

		assertTrue(new ClaimNextCommandService(durable, input -> new AlreadyCompleted())
				.claimNext(request(WorkerSegment.single())).isEmpty());
		assertEquals(ConsumptionStatus.DONE, durable.status(command.commandId()));
	}

	@Test
	void failedSlotSurvivesAMaterializationFailureAndIsReconciledLater() {
		RecordedCommand command = global(1, NOW);
		ProcessingFailure failure = new ProcessingFailure("business", "rejected", NOW);
		InMemoryCommandPort durable = new InMemoryCommandPort(command);
		List<String> calls = new ArrayList<>();
		CommandPort failingMaterialization = new CommandPort() {
			@Override public Optional<RecordedCommand> findNextReady(
					WorkerSegment segment, Optional<CommandOrderingKey> cursor) {
				return durable.findNextReady(segment, cursor);
			}
			@Override public void markCompleted(UUID commandId) {
				throw new UnsupportedOperationException();
			}
			@Override public void markFailed(UUID commandId, ProcessingFailure processingFailure) {
				calls.add("mark");
				throw new IllegalStateException("storage unavailable");
			}
		};
		FailConsumptionUseCase lifecycle = input -> {
			calls.add("slot");
			return ConsumptionOutcome.APPLIED;
		};

		assertThrows(IllegalStateException.class, () -> new FailCommandProcessingService(
				failingMaterialization, lifecycle).fail(
						new FailCommandProcessingInput(command.commandId(), ClaimToken.generate(), failure)));
		assertEquals(List.of("slot", "mark"), calls);
		assertEquals(ConsumptionStatus.PENDING, durable.status(command.commandId()));

		assertTrue(new ClaimNextCommandService(durable, input -> new AlreadyFailed(failure))
				.claimNext(request(WorkerSegment.single())).isEmpty());
		assertEquals(ConsumptionStatus.DONE, durable.status(command.commandId()));
		assertEquals(failure, durable.failures.get(command.commandId()));
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
				this.statuses.put(command.commandId(), ConsumptionStatus.PENDING);
			}
		}

		@Override
		public Optional<RecordedCommand> findNextReady(
				WorkerSegment segment, Optional<CommandOrderingKey> afterExclusive) {
			return commands.values().stream()
					.filter(command -> status(command.commandId()) == ConsumptionStatus.PENDING)
					.filter(command -> command.potId()
							.map(potId -> segment.owns(PartitionHash.forPot(potId))).orElse(true))
					.filter(command -> afterExclusive
							.map(cursor -> ordering(command).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(CommandProcessingServicesTest::ordering));
		}

		@Override
		public void markCompleted(UUID commandId) {
			update(commandId, ConsumptionStatus.DONE);
		}

		@Override
		public void markFailed(UUID commandId, ProcessingFailure failure) {
			failures.put(commandId, failure);
			update(commandId, ConsumptionStatus.DONE);
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
		public synchronized TryAcquireConsumptionResult tryAcquire(TryAcquireConsumptionInput input) {
			Claim current = claims.get(input.consumptionKey());
			if (current != null && current.isActiveAt(NOW)) {
				return new NotAcquiredBusy();
			}
			Claim acquired = Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease());
			claims.put(input.consumptionKey(), acquired);
			return new Acquired(acquired);
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
			return ((Acquired) tryAcquire(new TryAcquireConsumptionInput(
					CommandProcessingKeys.forCommand(commandId), WORKER, LEASE))).claim();
		}
	}
}
