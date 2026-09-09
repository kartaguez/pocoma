package com.kartaguez.pocoma.locator.consumption.event;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
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
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventSchedulingCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

/** Discovers Event/generation scheduling triggers and atomically ensures the full current catalogue. */
public final class EventConsumptionLocator implements ConsumptionLocator {
	private final PipelineDefinitionRegistry definitions;
	private final WorkerSegment segment;
	private final EventConsumptionDiscoveryPort discovery;
	private final EventPort events;
	private final Clock clock;
	private final ScheduleProjectionTasksForEventUseCase scheduleTasks;
	private final ConsumptionTechnicalFailureClassifier failureClassifier;

	public EventConsumptionLocator(PipelineDefinitionRegistry definitions, WorkerSegment segment,
			EventConsumptionDiscoveryPort discovery, EventPort events,
			ScheduleProjectionTasksForEventUseCase scheduleTasks,
			ConsumptionTechnicalFailureClassifier failureClassifier, Clock clock) {
		this.definitions = requireNonNull(definitions, "definitions must not be null");
		this.segment = requireNonNull(segment, "segment must not be null");
		this.discovery = requireNonNull(discovery, "discovery must not be null");
		this.events = requireNonNull(events, "events must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.scheduleTasks = requireNonNull(scheduleTasks, "scheduleTasks must not be null");
		this.failureClassifier = requireNonNull(failureClassifier, "failureClassifier must not be null");
	}

	@Override
	public ConsumptionSearch openSearch() {
		return new Search();
	}

	private final class Search implements ConsumptionSearch {
		private Optional<EventSchedulingOrderingKey> cursor = Optional.empty();
		private final Instant now = clock.instant();

		@Override
		public Optional<LocatedConsumption> next() {
			Optional<EventSchedulingCandidate> candidate = discovery.findNextEligibleCandidate(
					definitions.all(), segment, now, cursor);
			if (candidate.isEmpty()) return Optional.empty();
			EventSchedulingCandidate event = candidate.orElseThrow();
			cursor = Optional.of(event.orderingKey());
			UUID eventId = event.eventId();
			return Optional.of(new LocatedConsumption(key(eventId, event.trigger()),
					context -> execute(eventId, context.slotId()),
					failureClassifier));
		}
	}

	private ConsumptionExecutionResult execute(UUID eventId, UUID slotId) {
		RecordedEvent<? extends BusinessEvent> event = events.findById(eventId)
				.orElseThrow(() -> new RecordedEventNotFoundException(eventId));
		var creation = scheduleTasks.schedule(event);
		var input = new ConsumptionInput(slotId, "EVENT", event.eventId().toString(), event.event().version());
		if (creation instanceof EventTaskSchedulingResult.Rejected rejected) {
			return new ConsumptionExecutionResult(
					new BusinessConsumptionOutcome.Rejected(rejected.rejectionCode()), List.of(input), List.of());
		}
		EventTaskSchedulingResult.Scheduled scheduled = (EventTaskSchedulingResult.Scheduled) creation;
		List<ConsumptionResult> results = scheduled.generations().stream()
				.flatMap(materialized -> materialized.tasks().stream())
				.map(task -> new ConsumptionResult(slotId, "TASK", task.taskType(), task.taskId().toString(),
						OptionalLong.empty(), Optional.of("POT"),
						Optional.of(event.event().potId().value().toString()),
						OptionalLong.of(event.event().version()), task.createdAt()))
				.toList();
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(input), results);
	}

	private ConsumptionKey key(UUID eventId, PipelineDefinition trigger) {
		return new ConsumptionKey(
				new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PROJECTION_TASK_SCHEDULER", List.of(
						trigger.pipelineId().value(), Integer.toString(trigger.pipelineVersion()))));
	}
}
