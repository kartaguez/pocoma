package com.kartaguez.pocoma.locator.consumption.task;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;
import com.kartaguez.pocoma.engine.service.taskexecution.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

public final class TaskConsumptionLocator implements ConsumptionLocator {
	private final PipelineDefinition pipeline;
	private final WorkerSegment segment;
	private final Set<String> taskTypes;
	private final TaskConsumptionDiscoveryPort discovery;
	private final TaskPort tasks;
	private final Clock clock;
	private final RecordedTaskExecutionMapperRegistry mappers;
	private final ExecuteTaskUseCase executeTask;
	private final ConsumptionTechnicalFailureClassifier classifier;

	public TaskConsumptionLocator(PipelineDefinition pipeline, WorkerSegment segment, Set<String> taskTypes,
			TaskConsumptionDiscoveryPort discovery, TaskPort tasks, RecordedTaskExecutionMapperRegistry mappers,
			ExecuteTaskUseCase executeTask, ConsumptionTechnicalFailureClassifier classifier, Clock clock) {
		this.pipeline = requireNonNull(pipeline);
		this.segment = requireNonNull(segment);
		this.taskTypes = Set.copyOf(requireNonNull(taskTypes));
		if (this.taskTypes.isEmpty()) throw new IllegalArgumentException("taskTypes must not be empty");
		this.discovery = requireNonNull(discovery);
		this.tasks = requireNonNull(tasks);
		this.clock = requireNonNull(clock);
		this.mappers = requireNonNull(mappers);
		this.executeTask = requireNonNull(executeTask);
		this.classifier = requireNonNull(classifier);
	}

	@Override public ConsumptionSearch openSearch() { return new Search(); }

	private final class Search implements ConsumptionSearch {
		private Optional<TaskSearchCursor> cursor = Optional.empty();
		private final Instant now = clock.instant();
		@Override public Optional<LocatedConsumption> next() {
			while (true) {
				var candidate = discovery.findNextEligibleCandidate(pipeline, segment, now, cursor);
				if (candidate.isEmpty()) return Optional.empty();
				var task = candidate.orElseThrow();
				cursor = Optional.of(new TaskSearchCursor(task.createdAt(), task.taskId()));
				if (!taskTypes.contains(task.taskType())) continue;
				UUID taskId = task.taskId();
				return Optional.of(new LocatedConsumption(key(taskId),
						context -> execute(taskId, context.slotId()), classifier));
			}
		}
	}

	private ConsumptionExecutionResult execute(UUID taskId, UUID slotId) {
		var task = tasks.findById(taskId).orElseThrow(() -> new TaskRecordedInputNotFoundException(taskId));
		if (!pipeline.equals(task.pipeline()) || !taskTypes.contains(task.taskType())) {
			throw new TaskBindingMismatchException();
		}
		var mapper = mappers.find(task.pipeline(), task.taskType()).orElseThrow(TaskBindingMismatchException::new);
		TaskExecutionReport report = executeTask.executeTask(mapper.map(task));
		List<ConsumptionInput> inputs = report.inputs().stream()
				.map(input -> new ConsumptionInput(slotId, input.type(), input.id(), input.version())).toList();
		List<ConsumptionResult> results = report.artifacts().stream().map(artifact -> {
			var subject = artifact.subject();
			return new ConsumptionResult(slotId, artifact.namespace(), artifact.type(), artifact.id(),
					artifact.version(), subject.map(value -> value.type()), subject.map(value -> value.id()),
					subject.isPresent() ? OptionalLong.of(subject.orElseThrow().version()) : OptionalLong.empty(),
					artifact.createdAt());
		}).toList();
		var outcome = report instanceof TaskExecutionReport.Rejected rejected
				? new BusinessConsumptionOutcome.Rejected(rejected.rejectionCode())
				: new BusinessConsumptionOutcome.Success();
		return new ConsumptionExecutionResult(outcome, inputs, results);
	}

	private static ConsumptionKey key(UUID taskId) {
		return new ConsumptionKey(new ConsumableIdentity("TASK", List.of(taskId.toString())),
				new ConsumerIdentity("TASK_EXECUTOR", List.of()));
	}

	public static final class TaskBindingMismatchException extends RuntimeException
			implements com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure {
		public TaskBindingMismatchException() { super("Recorded Task does not match the configured binding"); }
		@Override public String failureCode() { return "TASK_BINDING_MISMATCH"; }
		@Override public String failureCategory() { return "TASK_CONFIGURATION"; }
	}
}
