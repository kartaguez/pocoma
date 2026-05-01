package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record ProjectionWorkerWakeEvent(ProjectionWorkerWakeSignal signal, PotId potId) {

	public ProjectionWorkerWakeEvent {
		Objects.requireNonNull(signal, "signal must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
	}
}
