package com.kartaguez.pocoma.engine.port.out.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;

class ProjectionMaterializationDiscoveryModelTest {
	private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final EventType EVENT_TYPE = new EventType("POT_CREATED");
	private static final ProjectionType PROJECTION_TYPE = new ProjectionType("READ_POT");
	private static final TargetObjectType TARGET_TYPE = new TargetObjectType("POT");
	private static final TargetObjectId TARGET_ID = new TargetObjectId("20000000-0000-0000-0000-000000000001");
	private static final Instant RECORDED_AT = Instant.parse("2026-09-26T10:00:00Z");

	@Test
	void carriesTheCanonicalEventMetadataAndExpandedProjectionType() {
		var candidate = candidate(42);

		assertEquals(EVENT_ID, candidate.eventId());
		assertEquals(EVENT_TYPE, candidate.eventType());
		assertEquals(PROJECTION_TYPE, candidate.projectionType());
		assertEquals(TARGET_TYPE, candidate.targetObjectType());
		assertEquals(TARGET_ID, candidate.targetObjectId());
		assertEquals(42, candidate.targetVersion());
		assertEquals(RECORDED_AT, candidate.recordedAt());
		assertEquals(new ProjectionMaterializationOrderingKey(RECORDED_AT, EVENT_ID, PROJECTION_TYPE),
				candidate.orderingKey());
	}

	@Test
	void rejectsAnInvalidCandidateOrOrderingKey() {
		assertThrows(IllegalArgumentException.class, () -> candidate(0));
		assertThrows(NullPointerException.class, () -> new ProjectionMaterializationCandidate(
				EVENT_ID, null, PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 1, RECORDED_AT));
		assertThrows(NullPointerException.class, () -> new ProjectionMaterializationOrderingKey(
				RECORDED_AT, EVENT_ID, null));
	}

	private static ProjectionMaterializationCandidate candidate(long version) {
		return new ProjectionMaterializationCandidate(
				EVENT_ID, EVENT_TYPE, PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, version, RECORDED_AT);
	}
}
