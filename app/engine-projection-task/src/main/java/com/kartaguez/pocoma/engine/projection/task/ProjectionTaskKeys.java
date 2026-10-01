package com.kartaguez.pocoma.engine.projection.task;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public final class ProjectionTaskKeys {
	public static final String CONSUMABLE_TYPE = "PROJECTION_TASK";
	public static final String CONSUMER_TYPE = "PROJECTION_EXECUTOR";

	private ProjectionTaskKeys() {}

	public static ConsumptionKey consumptionKey(ProjectionKey key) {
		return new ConsumptionKey(
				new ConsumableIdentity(CONSUMABLE_TYPE, List.of(
						key.projectionType().value(), key.targetObjectType().value(),
						key.targetObjectId().value(), Long.toString(key.targetVersion()))),
				new ConsumerIdentity(CONSUMER_TYPE, List.of(key.projectionType().value())));
	}

	public static int partitionHash(ProjectionKey key) {
		return List.of(key.projectionType().value(), key.targetObjectType().value(),
				key.targetObjectId().value(), Long.toString(key.targetVersion())).hashCode();
	}
}
