package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

import java.util.Set;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public interface ProjectionWorkerWakeBus {

	void publish(ProjectionWorkerWakeEvent event);

	ProjectionWorkerWakeSubscription subscribe(
			Set<ProjectionWorkerWakeSignal> signals,
			ProjectionPartition partition,
			Runnable listener);

	default void publish(ProjectionWorkerWakeSignal signal, PotId potId) {
		publish(new ProjectionWorkerWakeEvent(signal, potId));
	}

	static ProjectionWorkerWakeBus noop() {
		return new ProjectionWorkerWakeBus() {
			@Override
			public void publish(ProjectionWorkerWakeEvent event) {
			}

			@Override
			public ProjectionWorkerWakeSubscription subscribe(
					Set<ProjectionWorkerWakeSignal> signals,
					ProjectionPartition partition,
					Runnable listener) {
				return () -> {
				};
			}
		};
	}
}
