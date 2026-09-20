package com.kartaguez.pocoma.runtime.event.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;

/** Transitional dual-write: canonical task creation follows a successful legacy scheduling. */
final class CanonicalProjectionTaskScheduler implements ScheduleProjectionTasksForEventUseCase {
	private static final TargetObjectType POT = new TargetObjectType("POT");
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType POT_BALANCES = new ProjectionType("POT_BALANCES");
	private final ScheduleProjectionTasksForEventUseCase legacy;
	private final ProjectionTaskStorePort canonical;
	private final Clock clock;

	CanonicalProjectionTaskScheduler(ScheduleProjectionTasksForEventUseCase legacy,
			ProjectionTaskStorePort canonical, Clock clock) {
		this.legacy = requireNonNull(legacy); this.canonical = requireNonNull(canonical); this.clock = requireNonNull(clock);
	}

	@Override public EventTaskSchedulingResult schedule(RecordedEvent<? extends BusinessEvent> event) {
		var result = legacy.schedule(event);
		if (result instanceof EventTaskSchedulingResult.Scheduled) {
			var businessEvent = event.event();
			var target = new TargetObjectId(businessEvent.potId().value().toString());
			canonical.ensure(new ProjectionKey(READ_POT, POT, target, businessEvent.version()), clock.instant());
			canonical.ensure(new ProjectionKey(POT_BALANCES, POT, target, businessEvent.version()), clock.instant());
		}
		return result;
	}
}
