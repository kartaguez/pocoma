package com.kartaguez.pocoma.supra.worker.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class CommandWorkerSettingsTest {

	@Test
	void acceptsExactlyThreeTimesTheNormalDuration() {
		var settings = settings(Duration.ofSeconds(30), Duration.ofSeconds(10));

		assertEquals(Duration.ofSeconds(30), settings.claimLease().duration());
		assertEquals("command-1", settings.consumptionWorkerId().value());
	}

	@Test
	void rejectsAnInsufficientLease() {
		assertThrows(IllegalArgumentException.class,
				() -> settings(Duration.ofSeconds(29), Duration.ofSeconds(10)));
	}

	@Test
	void rejectsInvalidDurationsAndIdentity() {
		assertThrows(IllegalArgumentException.class,
				() -> new CommandWorkerSettings(true, " ", Duration.ofSeconds(1),
						Duration.ofSeconds(3), Duration.ofSeconds(1), WorkerSegment.single(), false));
		assertThrows(IllegalArgumentException.class,
				() -> new CommandWorkerSettings(true, "worker", Duration.ZERO,
						Duration.ofSeconds(3), Duration.ofSeconds(1), WorkerSegment.single(), false));
		assertThrows(IllegalArgumentException.class,
				() -> new CommandWorkerSettings(true, "worker", Duration.ofSeconds(1),
						Duration.ofSeconds(3), Duration.ZERO, WorkerSegment.single(), false));
	}

	private static CommandWorkerSettings settings(Duration lease, Duration normal) {
		return new CommandWorkerSettings(true, "command-1", Duration.ofSeconds(1), lease, normal,
				WorkerSegment.single(), false);
	}
}
