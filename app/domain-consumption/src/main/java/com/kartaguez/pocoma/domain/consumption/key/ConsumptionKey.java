package com.kartaguez.pocoma.domain.consumption.key;

/** Identifies one independently claimable durable consumption. */
public sealed interface ConsumptionKey
		permits CommandConsumptionKey, EventConsumptionKey, TaskConsumptionKey {
}
