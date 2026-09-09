package com.kartaguez.pocoma.runtime.event.consumption;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class MeteredProjectionTaskScheduler implements ScheduleProjectionTasksForEventUseCase {
	private final ScheduleProjectionTasksForEventUseCase delegate;
	private final MeterRegistry registry;

	MeteredProjectionTaskScheduler(ScheduleProjectionTasksForEventUseCase delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.registry = registry;
	}

	@Override
	public EventTaskSchedulingResult schedule(RecordedEvent<? extends BusinessEvent> event) {
		try {
			EventTaskSchedulingResult result = delegate.schedule(event);
			if (result instanceof EventTaskSchedulingResult.Scheduled scheduled) {
				if (scheduled.generations().isEmpty()) increment("zero_applicable");
				scheduled.generations().forEach(generation -> increment(
						generation.outcome() == TaskCreationOutcome.CREATED ? "created" : "adopted"));
			}
			else {
				increment("rejected");
			}
			return result;
		}
		catch (RuntimeException failure) {
			increment("invariant_or_failure");
			throw failure;
		}
	}

	private void increment(String outcome) {
		Counter.builder("pocoma.projection.task.scheduling")
				.tag("outcome", outcome).register(registry).increment();
	}
}
