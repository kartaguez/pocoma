package com.kartaguez.pocoma.engine.read.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.SourceVersionWatermark;

class ObserveSourceVersionServiceTest {

	@Test
	void delegatesTheIntentionalObservation() {
		var captured = new AtomicReference<ObserveSourceVersionInput>();
		SourceVersionWatermarkPersistencePort persistence = input -> {
			captured.set(input);
			return new SourceVersionObservation.Advanced(
					new SourceVersionWatermark(input.potId(), input.potVersion()));
		};
		var service = new ObserveSourceVersionService(persistence);
		var input = new ObserveSourceVersionInput(
				PotId.of(UUID.randomUUID()), 42, Instant.parse("2026-01-01T00:00:00Z"));

		var result = service.observe(input);

		assertEquals(input, captured.get());
		assertInstanceOf(SourceVersionObservation.Advanced.class, result);
		assertEquals(42, result.watermark().latestVersionSeen());
	}
}
