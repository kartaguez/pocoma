package com.kartaguez.pocoma.engine.command.model;

/** Explicit durable format discriminator for a RecordedCommand envelope. */
public enum RecordedCommandEnvelopeVersion {
	LEGACY_V1(1),
	TARGET_V2(2);

	private final int storageValue;

	RecordedCommandEnvelopeVersion(int storageValue) {
		this.storageValue = storageValue;
	}

	public int storageValue() {
		return storageValue;
	}

	public static RecordedCommandEnvelopeVersion fromStorageValue(int value) {
		return switch (value) {
			case 1 -> LEGACY_V1;
			case 2 -> TARGET_V2;
			default -> throw new IllegalStateException("Unsupported durable Command envelope version " + value);
		};
	}
}
