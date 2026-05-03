package com.kartaguez.pocoma.orchestrator.claimable.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class CapacityNotifierTest {

	@Test
	void publishesCapacityAvailableSignal() {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		CapacityNotifier<Integer> notifier = new CapacityNotifier<>(wakeBus, "CAPACITY_AVAILABLE", Duration.ZERO);

		notifier.notifyCapacityAvailable(42);

		assertEquals(List.of(new WorkWakeEvent<>("CAPACITY_AVAILABLE", 42)), wakeBus.events);
	}

	@Test
	void throttlesSignalsPerKey() {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		CapacityNotifier<Integer> notifier = new CapacityNotifier<>(
				wakeBus,
				"CAPACITY_AVAILABLE",
				Duration.ofSeconds(1));

		notifier.notifyCapacityAvailable(42);
		notifier.notifyCapacityAvailable(42);
		notifier.notifyCapacityAvailable(7);

		assertEquals(
				List.of(
						new WorkWakeEvent<>("CAPACITY_AVAILABLE", 42),
						new WorkWakeEvent<>("CAPACITY_AVAILABLE", 7)),
				wakeBus.events);
	}

	private static final class RecordingWakeBus implements WorkWakeBus<String, Integer> {
		private final List<WorkWakeEvent<String, Integer>> events = new ArrayList<>();

		@Override
		public void publish(WorkWakeEvent<String, Integer> event) {
			events.add(event);
		}

		@Override
		public WorkWakeSubscription subscribe(
				Set<String> signals,
				Predicate<Integer> keyPredicate,
				Runnable listener) {
			return () -> {
			};
		}
	}
}
