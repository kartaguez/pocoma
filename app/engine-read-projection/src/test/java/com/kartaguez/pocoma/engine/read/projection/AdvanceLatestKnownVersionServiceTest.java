package com.kartaguez.pocoma.engine.read.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;

class AdvanceLatestKnownVersionServiceTest {

	@Test
	void delegatesTheMonotoneAdvance() {
		var captured = new AtomicReference<AdvanceLatestKnownVersionInput>();
		LatestKnownVersionPersistencePort persistence = input -> {
			captured.set(input);
			return new LatestKnownVersionUpdate.Advanced(
					new LatestKnownVersion(input.potId(), input.candidateVersion()));
		};
		var service = new AdvanceLatestKnownVersionService(persistence);
		var input = new AdvanceLatestKnownVersionInput(
				PotId.of(UUID.randomUUID()), 42, Instant.parse("2026-01-01T00:00:00Z"));

		var result = service.advanceToAtLeast(input);

		assertEquals(input, captured.get());
		assertInstanceOf(LatestKnownVersionUpdate.Advanced.class, result);
		assertEquals(42, result.latestKnownVersion().latestKnownVersion());
	}
}
