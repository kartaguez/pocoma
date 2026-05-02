package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

public class ProjectionTaskExecutorEventListener {

	private final WorkWakeBus<String, PotId> wakeBus;

	ProjectionTaskExecutorEventListener(WorkWakeBus<String, PotId> wakeBus) {
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	@EventListener
	public void on(ProjectionTasksReadyEvent event) {
		wakeTaskExecutor(event.potId());
	}

	@EventListener
	public void on(ProjectionTaskProcessedEvent event) {
		wakeTaskExecutor(event.potId());
	}

	private void wakeTaskExecutor(PotId potId) {
		wakeBus.publish(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, potId);
	}
}
