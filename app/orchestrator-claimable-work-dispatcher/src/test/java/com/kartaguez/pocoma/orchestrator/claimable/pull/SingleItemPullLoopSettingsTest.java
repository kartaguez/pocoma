package com.kartaguez.pocoma.orchestrator.claimable.pull;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SingleItemPullLoopSettingsTest {

	@Test
	void acceptsValidSettings() {
		assertDoesNotThrow(() -> new SingleItemPullLoopSettings(true, "worker-1", Duration.ofMillis(1), false));
	}

	@Test
	void rejectsInvalidWorkerIdAndPollingInterval() {
		assertThrows(NullPointerException.class,
				() -> new SingleItemPullLoopSettings(true, null, Duration.ofSeconds(1), false));
		assertThrows(IllegalArgumentException.class,
				() -> new SingleItemPullLoopSettings(true, " ", Duration.ofSeconds(1), false));
		assertThrows(NullPointerException.class,
				() -> new SingleItemPullLoopSettings(true, "worker", null, false));
		assertThrows(IllegalArgumentException.class,
				() -> new SingleItemPullLoopSettings(true, "worker", Duration.ZERO, false));
		assertThrows(IllegalArgumentException.class,
				() -> new SingleItemPullLoopSettings(true, "worker", Duration.ofMillis(-1), false));
	}
}
