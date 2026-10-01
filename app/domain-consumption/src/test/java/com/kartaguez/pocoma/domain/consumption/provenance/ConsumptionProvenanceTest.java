package com.kartaguez.pocoma.domain.consumption.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ConsumptionProvenanceTest {

	private static final UUID SLOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

	@Test
	void representsActuallyReadInputs() {
		ConsumptionInput input = new ConsumptionInput(SLOT_ID, "POT", "p42", 45);
		assertEquals(45, input.subjectVersion());
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionInput(SLOT_ID, "POT", "p42", 0));
	}

	@Test
	void representsProducedArtifactAndSubjectVersions() {
		ConsumptionResult result = new ConsumptionResult(SLOT_ID, "PROJECTION", "BALANCE", "balance:p42",
				OptionalLong.of(7), Optional.of("POT"), Optional.of("p42"), OptionalLong.of(46), NOW);
		assertEquals(7, result.objectVersion().orElseThrow());
		assertEquals(46, result.subjectVersion().orElseThrow());
	}

	@Test
	void requiresTheCompleteSubjectTriple() {
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionResult(
				SLOT_ID, "EVENT", "UPDATED", "e1", OptionalLong.empty(),
				Optional.of("POT"), Optional.empty(), OptionalLong.of(2), NOW));
	}
}
