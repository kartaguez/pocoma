package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProjectionValueObjectsTest {
	private static final ProjectionType PROJECTION_TYPE = new ProjectionType("READ_POT");
	private static final TargetObjectType TARGET_TYPE = new TargetObjectType("POT");
	private static final TargetObjectId TARGET_ID = new TargetObjectId("pot-42");

	@Test
	void textualValueObjectsRejectNullAndBlankValues() {
		assertTextValueContract(ProjectionType::new);
		assertTextValueContract(TargetObjectType::new);
		assertTextValueContract(TargetObjectId::new);
		assertTextValueContract(ArtifactType::new);
		assertTextValueContract(ArtifactKey::new);
	}

	@Test
	void projectionKeyRequiresAllComponentsAndAPositiveTargetVersion() {
		var key = new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 1);
		assertEquals(1, key.targetVersion());
		assertThrows(NullPointerException.class, () -> new ProjectionKey(null, TARGET_TYPE, TARGET_ID, 1));
		assertThrows(NullPointerException.class, () -> new ProjectionKey(PROJECTION_TYPE, null, TARGET_ID, 1));
		assertThrows(NullPointerException.class, () -> new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, null, 1));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 0));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, -1));
	}

	@Test
	void projectionCopiesArtifactsAndDelegatesItsIdentity() {
		var key = new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 7);
		var artifact = new ProjectionArtifact(new ArtifactType("HEADER"), new ArtifactKey("main"), JsonNull.INSTANCE);
		var source = new ArrayList<>(List.of(artifact));
		var projection = new Projection(key, source);

		source.clear();
		assertEquals(List.of(artifact), projection.artifacts());
		assertThrows(UnsupportedOperationException.class, () -> projection.artifacts().clear());
		assertSame(PROJECTION_TYPE, projection.projectionType());
		assertSame(TARGET_TYPE, projection.targetObjectType());
		assertSame(TARGET_ID, projection.targetObjectId());
		assertEquals(7, projection.targetVersion());
	}

	@Test
	void projectionAndArtifactRejectEveryRequiredJavaNull() {
		var key = new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 1);
		var type = new ArtifactType("HEADER");
		var artifactKey = new ArtifactKey("main");
		assertThrows(NullPointerException.class, () -> new Projection(null, List.of()));
		assertThrows(NullPointerException.class, () -> new Projection(key, null));
		var artifactsWithNull = new ArrayList<ProjectionArtifact>();
		artifactsWithNull.add(null);
		assertThrows(NullPointerException.class, () -> new Projection(key, artifactsWithNull));
		assertThrows(NullPointerException.class, () -> new ProjectionArtifact(null, artifactKey, JsonNull.INSTANCE));
		assertThrows(NullPointerException.class, () -> new ProjectionArtifact(type, null, JsonNull.INSTANCE));
		assertThrows(NullPointerException.class, () -> new ProjectionArtifact(type, artifactKey, null));
	}

	@Test
	void projectionFailureRequiresItsThreeFieldsAndUuidKeepsEqualFailuresDistinct() {
		var key = new ProjectionKey(PROJECTION_TYPE, TARGET_TYPE, TARGET_ID, 1);
		var failedAt = Instant.parse("2026-09-19T10:15:30Z");
		var first = new ProjectionFailure(new ProjectionFailureId(UUID.randomUUID()), key, failedAt);
		var second = new ProjectionFailure(new ProjectionFailureId(UUID.randomUUID()), key, failedAt);

		assertNotEquals(first, second);
		assertThrows(NullPointerException.class, () -> new ProjectionFailureId(null));
		assertThrows(NullPointerException.class, () -> new ProjectionFailure(null, key, failedAt));
		assertThrows(NullPointerException.class, () -> new ProjectionFailure(first.id(), null, failedAt));
		assertThrows(NullPointerException.class, () -> new ProjectionFailure(first.id(), key, null));
	}

	private static void assertTextValueContract(TextValueFactory factory) {
		assertThrows(NullPointerException.class, () -> factory.create(null));
		assertThrows(IllegalArgumentException.class, () -> factory.create(""));
		assertThrows(IllegalArgumentException.class, () -> factory.create("  \t"));
	}

	@FunctionalInterface
	private interface TextValueFactory {
		Object create(String value);
	}
}
