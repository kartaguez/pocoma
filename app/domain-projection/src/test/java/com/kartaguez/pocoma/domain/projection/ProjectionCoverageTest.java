package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pipeline.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class ProjectionCoverageTest {
	private static final ProjectionGenerationIdentity GENERATION = new ProjectionGenerationIdentity(
			new ProjectionType("POT"), new PipelineDefinition(PipelineId.of("READ_POT"), 2), PotId.of(UUID.randomUUID()));

	@Test void validatesAndContainsOnlyItsContinuousRange() {
		var coverage = new ProjectionCoverage(GENERATION, 10, 20);
		assertFalse(coverage.contains(9)); assertTrue(coverage.contains(10));
		assertTrue(coverage.contains(20)); assertFalse(coverage.contains(21));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionCoverage(GENERATION, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionCoverage(GENERATION, 20, 19));
	}

	@Test void extensionsAreMonotone() {
		var coverage = new ProjectionCoverage(GENERATION, 10, 20);
		assertEquals(new ProjectionCoverage(GENERATION, 5, 20), coverage.extendFrom(5));
		assertEquals(new ProjectionCoverage(GENERATION, 10, 25), coverage.extendThrough(25));
		assertEquals(coverage, coverage.extendFrom(15));
		assertEquals(coverage, coverage.extendThrough(15));
	}
}
