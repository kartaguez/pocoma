package com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class JpaPipelineTaskStatusTest {

	@Test
	void keepsLegacyPersistedNames() {
		assertArrayEquals(new String[] {
				"PENDING", "CLAIMED", "ACCEPTED", "RUNNING", "DONE", "FAILED", "SUPERSEDED"
		}, java.util.Arrays.stream(JpaPipelineTaskStatus.values()).map(Enum::name).toArray(String[]::new));
	}
}
