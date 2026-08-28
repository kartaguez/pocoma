package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;

class ConsumptionSlotTest {

	private static final ConsumptionKey KEY = new ConsumptionKey("work", List.of("42"));

	@Test
	void appliesReadyAndTerminalTransitionsWithMonotoneRevisions() {
		ConsumptionSlot initial = ConsumptionSlot.initial(KEY);
		ConsumptionSlot acquired = initial.acquired();
		ConsumptionSlot released = acquired.released();

		assertEquals(ConsumptionStatus.READY, initial.status());
		assertEquals(0, initial.revision());
		assertEquals(ConsumptionStatus.READY, acquired.status());
		assertEquals(1, acquired.revision());
		assertEquals(ConsumptionStatus.READY, released.status());
		assertEquals(2, released.revision());
		assertEquals(ConsumptionStatus.COMPLETED, initial.completed().status());
		assertEquals(ConsumptionStatus.FAILED, initial.failed().status());
	}

	@Test
	void rejectsNegativeRevisionAndTransitionsFromTerminalStates() {
		assertThrows(IllegalArgumentException.class,
				() -> new ConsumptionSlot(KEY, -1, ConsumptionStatus.READY));
		assertThrows(IllegalStateException.class, () -> ConsumptionSlot.initial(KEY).completed().released());
		assertThrows(IllegalStateException.class, () -> ConsumptionSlot.initial(KEY).failed().acquired());
	}
}
