package com.kartaguez.pocoma.engine.port.in.taskcreation.usecase;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;

@FunctionalInterface
public interface ScheduleProjectionTasksForEventUseCase {
	EventTaskSchedulingResult schedule(RecordedEvent<? extends BusinessEvent> event);
}
