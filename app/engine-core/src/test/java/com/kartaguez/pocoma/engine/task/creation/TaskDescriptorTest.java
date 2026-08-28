package com.kartaguez.pocoma.engine.task.creation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaskDescriptorTest {

	@Test
	void describesSerializationReadyWorkWithoutDurableState() {
		TaskDescriptor descriptor = new TaskDescriptor("TYPE", "key", "{}", "partition");

		assertEquals("TYPE", descriptor.taskType());
		assertEquals("key", descriptor.taskKey());
		assertEquals("{}", descriptor.taskPayload());
		assertEquals("partition", descriptor.partitionKey());
	}

	@Test
	void validatesRequiredText() {
		assertThrows(IllegalArgumentException.class, () -> new TaskDescriptor(" ", "key", "{}", null));
		assertThrows(IllegalArgumentException.class, () -> new TaskDescriptor("TYPE", " ", "{}", null));
		assertThrows(IllegalArgumentException.class, () -> new TaskDescriptor("TYPE", "key", " ", null));
		assertThrows(IllegalArgumentException.class, () -> new TaskDescriptor("TYPE", "key", "{}", " "));
	}
}
