package com.kartaguez.pocoma.domain.consumption.key;

/** Opaque, structurally comparable identity of one logical consumption. */
public record ConsumptionKey(ConsumableIdentity consumable, ConsumerIdentity consumer) {

	public ConsumptionKey {
		java.util.Objects.requireNonNull(consumable, "consumable must not be null");
		java.util.Objects.requireNonNull(consumer, "consumer must not be null");
	}

}
