package com.kartaguez.pocoma.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskPayloadTest {

	@Test
	void canBeImplementedByAnIndependentTypedTask() {
		TaskPayload payload = new TestTask("work");

		assertEquals("work", ((TestTask) payload).value());
	}

	private record TestTask(String value) implements TaskPayload {
	}
}
