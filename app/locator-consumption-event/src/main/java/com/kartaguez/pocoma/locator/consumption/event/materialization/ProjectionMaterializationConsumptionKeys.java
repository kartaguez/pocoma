package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;

public final class ProjectionMaterializationConsumptionKeys {
	private static final String CONSUMABLE_TYPE = "EVENT";
	private static final String CONSUMER_TYPE = "PROJECTION_TASK_MATERIALIZER";

	private ProjectionMaterializationConsumptionKeys() {
	}

	public static ConsumptionKey consumptionKey(ProjectionMaterializationCandidate candidate) {
		requireNonNull(candidate, "candidate must not be null");
		return new ConsumptionKey(
				new ConsumableIdentity(CONSUMABLE_TYPE, List.of(candidate.eventId().toString())),
				new ConsumerIdentity(CONSUMER_TYPE, List.of(candidate.projectionType().value())));
	}
}
