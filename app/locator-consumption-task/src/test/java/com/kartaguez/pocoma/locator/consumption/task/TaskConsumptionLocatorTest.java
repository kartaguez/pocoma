package com.kartaguez.pocoma.locator.consumption.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;
import com.kartaguez.pocoma.engine.service.taskexecution.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.engine.taskexecution.model.BusinessObjectVersion;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;

class TaskConsumptionLocatorTest {
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balance-projection"), 2);
	private static final UUID TASK_ID = UUID.randomUUID();
	private static final PotId POT_ID = PotId.of(UUID.randomUUID());

	@Test
	void executionReloadsAuthoritativeTaskAndBuildsProvenanceFromIt() {
		RecordedTask located = task(41, "{\"version\":41}");
		RecordedTask authoritative = task(42, "{\"version\":42}");
		ReloadingTaskPort port = new ReloadingTaskPort(located, authoritative);
		RecordingMapper mapper = new RecordingMapper();
		var locator = locator(port, mapper, input -> new TaskExecutionReport.Succeeded(
				List.of(new BusinessObjectVersion("POT", POT_ID.value().toString(),
						((TestPayload) input.task()).version())),
				List.of()));

		var locatedConsumption = locator.openSearch().next().orElseThrow();
		UUID slotId = UUID.randomUUID();
		var result = locatedConsumption.execution().execute(
				new ConsumptionExecutionContext(slotId, ClaimId.generate()));

		assertEquals(1, port.candidateReads);
		assertEquals(1, port.authoritativeReads);
		assertEquals(authoritative, mapper.mapped);
		assertEquals(42, result.inputs().getFirst().subjectVersion());
		assertEquals(slotId, result.inputs().getFirst().slotId());
		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
	}

	@Test
	void functionalRejectionIsReturnedAsRejectedOutcome() {
		RecordedTask task = task(42, "{\"version\":42}");
		var locator = locator(new ReloadingTaskPort(task, task), new RecordingMapper(), input ->
				new TaskExecutionReport.Rejected("BALANCE_NOT_APPLICABLE",
						List.of(new BusinessObjectVersion("POT", POT_ID.value().toString(), 42)), List.of()));

		var result = locator.openSearch().next().orElseThrow().execution().execute(
				new ConsumptionExecutionContext(UUID.randomUUID(), ClaimId.generate()));

		assertEquals("BALANCE_NOT_APPLICABLE",
				assertInstanceOf(BusinessConsumptionOutcome.Rejected.class, result.outcome()).rejectionCode());
		assertEquals(1, result.inputs().size());
		assertEquals(List.of(), result.results());
	}

	private static TaskConsumptionLocator locator(TaskPort tasks, RecordingMapper mapper,
			com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase execute) {
		return new TaskConsumptionLocator(PIPELINE, new WorkerSegment(0, 1), Set.of("COMPUTE"), tasks,
				new RecordedTaskExecutionMapperRegistry(List.of(mapper)), execute,
				failure -> { throw new AssertionError("classifier must not be used by the locator"); });
	}

	private static RecordedTask task(long version, String payload) {
		return new RecordedTask(TASK_ID, PIPELINE, POT_ID, version, Instant.parse("2026-01-01T00:00:00Z"),
				"COMPUTE", payload, Optional.empty());
	}

	private record TestPayload(long version) implements TaskPayload {}

	private static final class RecordingMapper implements RecordedTaskExecutionMapper<TestPayload> {
		private RecordedTask mapped;
		@Override public PipelineDefinition pipeline() { return PIPELINE; }
		@Override public String taskType() { return "COMPUTE"; }
		@Override public ExecuteTaskInput<TestPayload> map(RecordedTask task) {
			mapped = task;
			return new ExecuteTaskInput<>(PIPELINE, "COMPUTE", new TestPayload(task.targetVersion()));
		}
	}

	private static final class ReloadingTaskPort implements TaskPort {
		private final RecordedTask located;
		private final RecordedTask authoritative;
		private int candidateReads;
		private int authoritativeReads;
		private ReloadingTaskPort(RecordedTask located, RecordedTask authoritative) {
			this.located = located;
			this.authoritative = authoritative;
		}
		@Override public Optional<RecordedTask> findNextCandidate(PipelineDefinition pipeline,
				WorkerSegment segment, Optional<TaskSearchCursor> afterExclusive) {
			candidateReads++;
			return candidateReads == 1 ? Optional.of(located) : Optional.empty();
		}
		@Override public Optional<RecordedTask> findById(UUID taskId) {
			authoritativeReads++;
			return Optional.of(authoritative);
		}
	}
}
