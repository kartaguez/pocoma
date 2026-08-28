package com.kartaguez.pocoma.domain.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PipelineDefinitionTest {

	@Test
	void validatesPipelineIdentity() {
		assertThrows(NullPointerException.class, () -> PipelineId.of(null));
		assertThrows(IllegalArgumentException.class, () -> PipelineId.of(""));
		assertThrows(IllegalArgumentException.class, () -> PipelineId.of("  "));
		assertEquals("balances", PipelineId.of("balances").value());
	}

	@Test
	void validatesAndStructurallyComparesDefinitions() {
		PipelineDefinition versionOne = new PipelineDefinition(PipelineId.of("balances"), 1);

		assertEquals(new PipelineDefinition(PipelineId.of("balances"), 1), versionOne);
		assertNotEquals(new PipelineDefinition(PipelineId.of("balances"), 2), versionOne);
		assertNotEquals(new PipelineDefinition(PipelineId.of("settlements"), 1), versionOne);
		assertThrows(NullPointerException.class, () -> new PipelineDefinition(null, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new PipelineDefinition(PipelineId.of("balances"), 0));
	}
}
