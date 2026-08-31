package com.kartaguez.pocoma.locator.consumption.event;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

/** Discovers independent Event/pipeline consumptions and supplies their atomic business callback. */
public final class EventConsumptionLocator implements ConsumptionLocator {
	private final PipelineDefinition pipeline;
	private final WorkerSegment segment;
	private final EventPort events;
	private final CreateTasksForEventUseCase createTasks;
	private final ConsumptionTechnicalFailureClassifier failureClassifier;

	public EventConsumptionLocator(PipelineDefinition pipeline, WorkerSegment segment, EventPort events,
			CreateTasksForEventUseCase createTasks, ConsumptionTechnicalFailureClassifier failureClassifier) {
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.segment = requireNonNull(segment, "segment must not be null");
		this.events = requireNonNull(events, "events must not be null");
		this.createTasks = requireNonNull(createTasks, "createTasks must not be null");
		this.failureClassifier = requireNonNull(failureClassifier, "failureClassifier must not be null");
	}

	@Override
	public ConsumptionSearch openSearch() {
		return new Search();
	}

	private final class Search implements ConsumptionSearch {
		private Optional<EventOrderingKey> cursor = Optional.empty();

		@Override
		public Optional<LocatedConsumption> next() {
			Optional<RecordedEvent<? extends BusinessEvent>> candidate = events.findNextCandidate(pipeline, segment, cursor);
			if (candidate.isEmpty()) return Optional.empty();
			RecordedEvent<? extends BusinessEvent> event = candidate.orElseThrow();
			cursor = Optional.of(new EventOrderingKey(event.event().version(), event.recordedAt(), event.eventId()));
			UUID eventId = event.eventId();
			return Optional.of(new LocatedConsumption(key(event), context -> execute(eventId, context.slotId()),
					failureClassifier));
		}
	}

	private ConsumptionExecutionResult execute(UUID eventId, UUID slotId) {
		RecordedEvent<? extends BusinessEvent> event = events.findById(eventId)
				.orElseThrow(() -> new RecordedEventNotFoundException(eventId));
		var creation = createTasks.createTasks(new CreateTasksForEventInput(event, pipeline));
		var input = new ConsumptionInput(slotId, "EVENT", event.eventId().toString(), event.event().version());
		if (creation instanceof TaskCreationResult.Rejected rejected) {
			return new ConsumptionExecutionResult(
					new BusinessConsumptionOutcome.Rejected(rejected.rejectionCode()), List.of(input), List.of());
		}
		TaskCreationResult.Materialized materialized = (TaskCreationResult.Materialized) creation;
		List<ConsumptionResult> results = materialized.tasks().stream()
				.map(task -> new ConsumptionResult(slotId, "TASK", task.taskType(), task.taskId().toString(),
						OptionalLong.empty(), Optional.of("POT"),
						Optional.of(event.event().potId().value().toString()),
						OptionalLong.of(event.event().version()), task.createdAt()))
				.toList();
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(input), results);
	}

	private ConsumptionKey key(RecordedEvent<? extends BusinessEvent> event) {
		return new ConsumptionKey(
				new ConsumableIdentity("EVENT", List.of(event.eventId().toString())),
				new ConsumerIdentity("PIPELINE", List.of(
						pipeline.pipelineId().value(), Integer.toString(pipeline.pipelineVersion()))));
	}
}
