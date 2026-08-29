package com.kartaguez.pocoma.engine.service.processing.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
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
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.CompleteTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.FailTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ReleaseTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskOrderingKey;

class TaskProcessingServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerId WORKER = new WorkerId("task-worker");
	private static final PipelineDefinition BALANCES = pipeline("balances", 1);
	private static final PipelineDefinition SETTLEMENTS = pipeline("settlements", 1);

	@Test
	void selectsByTargetVersionThenCreationTimestampThenTaskId() {
		RecordedTask firstVersion = task(3, BALANCES, uuid(90), 1, NOW.plusSeconds(10));
		RecordedTask oldestAtSecondVersion = task(4, BALANCES, uuid(90), 2, NOW);
		RecordedTask firstTie = task(1, BALANCES, uuid(90), 2, NOW.plusSeconds(1));
		RecordedTask secondTie = task(2, BALANCES, uuid(90), 2, NOW.plusSeconds(1));
		InMemoryTaskPort tasks = new InMemoryTaskPort(
				secondTie, firstTie, oldestAtSecondVersion, firstVersion);

		assertEquals(firstVersion.taskId(), tasks.findNextReady(BALANCES, WorkerSegment.single(), Optional.empty())
				.orElseThrow().taskId());
		assertEquals(oldestAtSecondVersion.taskId(), tasks.findNextReady(BALANCES, WorkerSegment.single(),
				Optional.of(ordering(firstVersion))).orElseThrow().taskId());
		assertEquals(firstTie.taskId(), tasks.findNextReady(BALANCES, WorkerSegment.single(),
				Optional.of(ordering(oldestAtSecondVersion))).orElseThrow().taskId());
	}

	@Test
	void filtersByExactPipelineAndPipelinePotSegment() {
		UUID potId = uuid(90);
		RecordedTask balances = task(1, BALANCES, potId, 1, NOW);
		RecordedTask settlements = task(2, SETTLEMENTS, potId, 1, NOW);
		InMemoryTaskPort tasks = new InMemoryTaskPort(balances, settlements);
		int owner = Math.floorMod(PartitionHash.forPipelinePot("balances", potId).value(), 4);

		for (int index = 0; index < 4; index++) {
			Optional<RecordedTask> selected = tasks.findNextReady(
					BALANCES, new WorkerSegment(index, 4), Optional.empty());
			assertEquals(index == owner, selected.isPresent());
			selected.ifPresent(task -> assertEquals(balances.taskId(), task.taskId()));
		}
	}

	@Test
	void taskIdDefinesOneConsumptionEvenAcrossPipelineInputs() {
		UUID taskId = uuid(1);
		RecordedTask balances = task(taskId, BALANCES, uuid(90), 1, NOW);
		RecordedTask settlements = task(taskId, SETTLEMENTS, uuid(91), 1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();

		assertTrue(new ClaimNextTaskService(new InMemoryTaskPort(balances), consumption)
				.claimNext(request(BALANCES)).isPresent());
		assertTrue(new ClaimNextTaskService(new InMemoryTaskPort(settlements), consumption)
				.claimNext(request(SETTLEMENTS)).isEmpty());
	}

	@Test
	void aCasLoserContinuesToTheNextTaskAndReturnsOnlyOne() {
		RecordedTask first = task(1, BALANCES, uuid(90), 1, NOW);
		RecordedTask second = task(2, BALANCES, uuid(90), 2, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		consumption.tryAcquire(new TryAcquireConsumptionInput(
				TaskProcessingKeys.forTask(first.taskId()), new WorkerId("other"), LEASE));

		TaskClaimResult result = new ClaimNextTaskService(
				new InMemoryTaskPort(second, first), consumption).claimNext(request(BALANCES)).orElseThrow();

		assertEquals(second.taskId(), result.task().taskId());
		assertEquals(TaskProcessingKeys.forTask(second.taskId()), result.claim().consumptionKey());
	}

	@Test
	void anExpiredClaimIsReplacedWithANewToken() {
		RecordedTask task = task(1, BALANCES, uuid(90), 1, NOW);
		InMemoryConsumption consumption = new InMemoryConsumption();
		ConsumptionKey key = TaskProcessingKeys.forTask(task.taskId());
		Claim expired = Claim.active(ClaimId.generate(), key, ClaimToken.generate(), WORKER,
				NOW.minusSeconds(31), LEASE);
		consumption.claims.put(key, expired);

		Claim reclaimed = new ClaimNextTaskService(new InMemoryTaskPort(task), consumption)
				.claimNext(request(BALANCES)).orElseThrow().claim();

		assertNotEquals(expired.token(), reclaimed.token());
	}

	@Test
	void completeFailAndReleaseRespectFencingAndDurableTaskTransitions() {
		RecordedTask completedTask = task(1, BALANCES, uuid(90), 1, NOW);
		RecordedTask failedTask = task(2, BALANCES, uuid(90), 2, NOW);
		RecordedTask releasedTask = task(3, BALANCES, uuid(90), 3, NOW);
		InMemoryTaskPort tasks = new InMemoryTaskPort(completedTask, failedTask, releasedTask);
		InMemoryConsumption consumption = new InMemoryConsumption();
		Claim completed = consumption.acquire(completedTask.taskId());
		Claim failed = consumption.acquire(failedTask.taskId());
		Claim released = consumption.acquire(releasedTask.taskId());
		ProcessingFailure failure = new ProcessingFailure("handler", "boom", NOW);

		assertEquals(ConsumptionOutcome.APPLIED,
				new CompleteTaskProcessingService(tasks, consumption).complete(
						new CompleteTaskProcessingInput(completedTask.taskId(), completed.token())));
		assertEquals(ConsumptionOutcome.APPLIED,
				new FailTaskProcessingService(tasks, consumption).fail(
						new FailTaskProcessingInput(failedTask.taskId(), failed.token(), failure)));
		assertEquals(ConsumptionOutcome.APPLIED,
				new ReleaseTaskProcessingService(consumption).release(
						new ReleaseTaskProcessingInput(releasedTask.taskId(), released.token())));

		assertEquals(ConsumptionStatus.COMPLETED, tasks.status(completedTask.taskId()));
		assertEquals(ConsumptionStatus.FAILED, tasks.status(failedTask.taskId()));
		assertEquals(failure, tasks.failures.get(failedTask.taskId()));
		assertEquals(ConsumptionStatus.READY, tasks.status(releasedTask.taskId()));

		ClaimToken stale = ClaimToken.generate();
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteTaskProcessingService(tasks, consumption).complete(
						new CompleteTaskProcessingInput(releasedTask.taskId(), stale)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new FailTaskProcessingService(tasks, consumption).fail(
						new FailTaskProcessingInput(releasedTask.taskId(), stale, failure)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new ReleaseTaskProcessingService(consumption).release(
						new ReleaseTaskProcessingInput(releasedTask.taskId(), stale)));
		assertEquals(ConsumptionStatus.READY, tasks.status(releasedTask.taskId()));
	}

	@Test
	void reconcilesTerminalTasksAndContinuesToTheNextClaimableCandidate() {
		RecordedTask completed = task(1, BALANCES, uuid(90), 1, NOW);
		RecordedTask failed = task(2, BALANCES, uuid(90), 2, NOW);
		RecordedTask claimable = task(3, BALANCES, uuid(90), 3, NOW);
		ProcessingFailure failure = new ProcessingFailure("handler", "boom", NOW);
		InMemoryTaskPort tasks = new InMemoryTaskPort(completed, failed, claimable);
		TryAcquireConsumptionUseCase consumption = input -> {
			if (input.consumptionKey().equals(TaskProcessingKeys.forTask(completed.taskId()))) {
				return new AlreadyCompleted();
			}
			if (input.consumptionKey().equals(TaskProcessingKeys.forTask(failed.taskId()))) {
				return new AlreadyFailed(failure);
			}
			return new Acquired(Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease()));
		};

		TaskClaimResult result = new ClaimNextTaskService(tasks, consumption)
				.claimNext(request(BALANCES)).orElseThrow();

		assertEquals(ConsumptionStatus.COMPLETED, tasks.status(completed.taskId()));
		assertEquals(ConsumptionStatus.FAILED, tasks.status(failed.taskId()));
		assertEquals(failure, tasks.failures.get(failed.taskId()));
		assertEquals(claimable.taskId(), result.task().taskId());
	}

	@Test
	void terminalSlotSurvivesATaskMaterializationFailureAndIsReconciledLater() {
		RecordedTask task = task(1, BALANCES, uuid(90), 1, NOW);
		InMemoryTaskPort durable = new InMemoryTaskPort(task);
		List<String> calls = new ArrayList<>();
		TaskPort failingMaterialization = new TaskPort() {
			@Override public Optional<RecordedTask> findNextReady(
					PipelineDefinition pipeline, WorkerSegment segment, Optional<TaskOrderingKey> cursor) {
				return durable.findNextReady(pipeline, segment, cursor);
			}
			@Override public void markCompleted(UUID taskId) {
				calls.add("mark");
				throw new IllegalStateException("storage unavailable");
			}
			@Override public void markFailed(UUID taskId, ProcessingFailure failure) {
				throw new UnsupportedOperationException();
			}
		};
		CompleteConsumptionUseCase lifecycle = input -> {
			calls.add("slot");
			return ConsumptionOutcome.APPLIED;
		};

		assertThrows(IllegalStateException.class, () -> new CompleteTaskProcessingService(
				failingMaterialization, lifecycle).complete(
						new CompleteTaskProcessingInput(task.taskId(), ClaimToken.generate())));
		assertEquals(List.of("slot", "mark"), calls);
		assertEquals(ConsumptionStatus.READY, durable.status(task.taskId()));

		assertTrue(new ClaimNextTaskService(durable, input -> new AlreadyCompleted())
				.claimNext(request(BALANCES)).isEmpty());
		assertEquals(ConsumptionStatus.COMPLETED, durable.status(task.taskId()));
	}

	@Test
	void failedSlotSurvivesATaskMaterializationFailureAndIsReconciledLater() {
		RecordedTask task = task(1, BALANCES, uuid(90), 1, NOW);
		ProcessingFailure failure = new ProcessingFailure("handler", "boom", NOW);
		InMemoryTaskPort durable = new InMemoryTaskPort(task);
		List<String> calls = new ArrayList<>();
		TaskPort failingMaterialization = new TaskPort() {
			@Override public Optional<RecordedTask> findNextReady(
					PipelineDefinition pipeline, WorkerSegment segment, Optional<TaskOrderingKey> cursor) {
				return durable.findNextReady(pipeline, segment, cursor);
			}
			@Override public void markCompleted(UUID taskId) {
				throw new UnsupportedOperationException();
			}
			@Override public void markFailed(UUID taskId, ProcessingFailure processingFailure) {
				calls.add("mark");
				throw new IllegalStateException("storage unavailable");
			}
		};
		FailConsumptionUseCase lifecycle = input -> {
			calls.add("slot");
			return ConsumptionOutcome.APPLIED;
		};

		assertThrows(IllegalStateException.class, () -> new FailTaskProcessingService(
				failingMaterialization, lifecycle).fail(
						new FailTaskProcessingInput(task.taskId(), ClaimToken.generate(), failure)));
		assertEquals(List.of("slot", "mark"), calls);
		assertEquals(ConsumptionStatus.READY, durable.status(task.taskId()));

		assertTrue(new ClaimNextTaskService(durable, input -> new AlreadyFailed(failure))
				.claimNext(request(BALANCES)).isEmpty());
		assertEquals(ConsumptionStatus.FAILED, durable.status(task.taskId()));
		assertEquals(failure, durable.failures.get(task.taskId()));
	}

	private static ClaimNextTaskInput request(PipelineDefinition pipeline) {
		return new ClaimNextTaskInput(WORKER, LEASE, WorkerSegment.single(), pipeline);
	}

	private static TaskOrderingKey ordering(RecordedTask task) {
		return new TaskOrderingKey(task.targetVersion(), task.createdAt(), task.taskId());
	}

	private static RecordedTask task(
			int id, PipelineDefinition pipeline, UUID potId, long version, Instant createdAt) {
		return task(uuid(id), pipeline, potId, version, createdAt);
	}

	private static RecordedTask task(
			UUID taskId, PipelineDefinition pipeline, UUID potId, long version, Instant createdAt) {
		return new RecordedTask(taskId, pipeline, PotId.of(potId), version, createdAt,
				"COMPUTE", "{\"targetVersion\":" + version + "}", Optional.empty());
	}

	private static PipelineDefinition pipeline(String id, int version) {
		return new PipelineDefinition(PipelineId.of(id), version);
	}

	private static UUID uuid(int suffix) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
	}

	private static final class InMemoryTaskPort implements TaskPort {
		private final Map<UUID, RecordedTask> tasks = new HashMap<>();
		private final Map<UUID, ConsumptionStatus> statuses = new HashMap<>();
		private final Map<UUID, ProcessingFailure> failures = new HashMap<>();

		private InMemoryTaskPort(RecordedTask... tasks) {
			for (RecordedTask task : tasks) {
				this.tasks.put(task.taskId(), task);
				this.statuses.put(task.taskId(), ConsumptionStatus.READY);
			}
		}

		@Override
		public Optional<RecordedTask> findNextReady(
				PipelineDefinition pipeline, WorkerSegment segment, Optional<TaskOrderingKey> afterExclusive) {
			return tasks.values().stream()
					.filter(task -> status(task.taskId()) == ConsumptionStatus.READY)
					.filter(task -> task.pipeline().equals(pipeline))
					.filter(task -> segment.owns(PartitionHash.forPipelinePot(
							pipeline.pipelineId().value(), task.potId().value())))
					.filter(task -> afterExclusive.map(cursor -> ordering(task).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(TaskProcessingServicesTest::ordering));
		}

		@Override
		public void markCompleted(UUID taskId) {
			statuses.put(taskId, ConsumptionStatus.COMPLETED);
		}

		@Override
		public void markFailed(UUID taskId, ProcessingFailure failure) {
			failures.put(taskId, failure);
			statuses.put(taskId, ConsumptionStatus.FAILED);
		}

		private ConsumptionStatus status(UUID taskId) {
			return statuses.get(taskId);
		}
	}

	private static final class InMemoryConsumption implements TryAcquireConsumptionUseCase,
			CompleteConsumptionUseCase, FailConsumptionUseCase, ReleaseConsumptionUseCase {

		private final Map<ConsumptionKey, Claim> claims = new HashMap<>();

		private Claim acquire(UUID taskId) {
			return ((Acquired) tryAcquire(new TryAcquireConsumptionInput(
					TaskProcessingKeys.forTask(taskId), WORKER, LEASE))).claim();
		}

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
			claims.put(key, claim.invalidateAt(NOW));
			return ConsumptionOutcome.APPLIED;
		}
	}
}
