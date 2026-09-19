package com.kartaguez.pocoma.engine.service.projection.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.exception.projection.read.StoredProjectionInvariantViolationException;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;

class ExactProjectionReadServiceTest {
	private static final ProjectionType PROJECTION_TYPE = new ProjectionType("TEST");
	private static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("POT");
	private static final ArtifactType ARTIFACT_TYPE = new ArtifactType("HEADER");

	@Test
	void rejectsNullKeyBeforeReading() {
		var port = new RecordingProjectionReadPort();
		var service = service(port);

		assertThrows(NullPointerException.class, () -> service.get(null, definition()));

		port.assertNoCalls();
	}

	@Test
	void rejectsNullDefinitionBeforeReading() {
		var port = new RecordingProjectionReadPort();
		var service = service(port);

		assertThrows(NullPointerException.class, () -> service.get(key(1), null));

		port.assertNoCalls();
	}

	@Test
	void rejectsMismatchedProjectionTypeBeforeReading() {
		var port = new RecordingProjectionReadPort();
		var service = service(port);
		var otherDefinition = new ProjectionDefinition(new ProjectionType("OTHER"),
				TARGET_OBJECT_TYPE, List.of());

		assertThrows(IllegalArgumentException.class, () -> service.get(key(1), otherDefinition));

		port.assertNoCalls();
	}

	@Test
	void rejectsMismatchedTargetObjectTypeBeforeReading() {
		var port = new RecordingProjectionReadPort();
		var service = service(port);
		var otherDefinition = new ProjectionDefinition(PROJECTION_TYPE,
				new TargetObjectType("OTHER"), List.of());

		assertThrows(IllegalArgumentException.class, () -> service.get(key(1), otherDefinition));

		port.assertNoCalls();
	}

	@Test
	void returnsReadyWithTheValidatedStoredProjection() {
		ProjectionKey key = key(1);
		Projection stored = new Projection(key, List.of());
		var port = new RecordingProjectionReadPort(Optional.of(stored), false);

		var ready = assertInstanceOf(ProjectionReadResult.Ready.class,
				service(port).get(key, definition()));

		assertSame(stored, ready.projection().projection());
		assertEquals(key, ready.projectionKey());
		assertEquals(1, port.findProjectionCalls);
		assertEquals(0, port.hasFailureCalls);
	}

	@Test
	void projectionPresenceHasPriorityOverHistoricalFailures() {
		ProjectionKey key = key(2);
		var port = new RecordingProjectionReadPort(
				Optional.of(new Projection(key, List.of())), true);

		assertInstanceOf(ProjectionReadResult.Ready.class,
				service(port).get(key, definition()));

		assertEquals(0, port.hasFailureCalls);
	}

	@Test
	void returnsFailedOnlyWhenProjectionIsAbsentAndFailureExists() {
		ProjectionKey key = key(3);
		var port = new RecordingProjectionReadPort(Optional.empty(), true);

		var failed = assertInstanceOf(ProjectionReadResult.Failed.class,
				service(port).get(key, definition()));

		assertEquals(key, failed.projectionKey());
		assertEquals(1, port.findProjectionCalls);
		assertEquals(1, port.hasFailureCalls);
	}

	@Test
	void returnsNotReadyWhenProjectionAndFailureAreAbsent() {
		ProjectionKey key = key(4);
		var port = new RecordingProjectionReadPort(Optional.empty(), false);

		var notReady = assertInstanceOf(ProjectionReadResult.NotReady.class,
				service(port).get(key, definition()));

		assertEquals(key, notReady.projectionKey());
		assertEquals(1, port.findProjectionCalls);
		assertEquals(1, port.hasFailureCalls);
	}

	@Test
	void rejectsAStoredProjectionWithADifferentExactKeyBeforeValidation() {
		ProjectionKey requestedKey = key(5);
		ProjectionKey returnedKey = key(6);
		Projection stored = new Projection(returnedKey, List.of(artifact()));
		var port = new RecordingProjectionReadPort(Optional.of(stored), true);
		var schemaCalls = new AtomicInteger();
		var validator = new ProjectionValidator((schema, payload) -> {
			schemaCalls.incrementAndGet();
			throw new AssertionError("validator must not be called for a mismatched key");
		});

		var exception = assertThrows(StoredProjectionInvariantViolationException.class,
				() -> new ExactProjectionReadService(port, validator)
						.get(requestedKey, definitionWithArtifact()));

		assertEquals(requestedKey, exception.projectionKey());
		assertNull(exception.getCause());
		assertEquals(0, schemaCalls.get());
		assertEquals(1, port.findProjectionCalls);
		assertEquals(0, port.hasFailureCalls);
	}

	@Test
	void wrapsStoredProjectionValidationFailureAndNeverChecksFailures() {
		ProjectionKey key = key(7);
		Projection stored = new Projection(key, List.of(artifact()));
		var port = new RecordingProjectionReadPort(Optional.of(stored), true);
		var validator = new ProjectionValidator((schema, payload) -> false);

		var exception = assertThrows(StoredProjectionInvariantViolationException.class,
				() -> new ExactProjectionReadService(port, validator)
						.get(key, definitionWithArtifact()));

		assertEquals(key, exception.projectionKey());
		assertInstanceOf(ProjectionValidationException.class, exception.getCause());
		assertEquals(0, port.hasFailureCalls);
	}

	@Test
	void resultVariantsRejectNullAndReadyDoesNotDuplicateTheKey() {
		ValidatedProjection validated = new ProjectionValidator((schema, payload) -> true)
				.validate(definition(), new Projection(key(8), List.of()));

		var ready = new ProjectionReadResult.Ready(validated);
		assertEquals(key(8), ready.projectionKey());
		assertEquals(1, ProjectionReadResult.Ready.class.getRecordComponents().length);
		assertEquals(ValidatedProjection.class,
				ProjectionReadResult.Ready.class.getRecordComponents()[0].getType());
		assertThrows(NullPointerException.class, () -> new ProjectionReadResult.Ready(null));
		assertThrows(NullPointerException.class, () -> new ProjectionReadResult.Failed(null));
		assertThrows(NullPointerException.class, () -> new ProjectionReadResult.NotReady(null));
	}

	@Test
	void constructorRejectsNullDependencies() {
		var port = new RecordingProjectionReadPort();
		var validator = new ProjectionValidator((schema, payload) -> true);

		assertThrows(NullPointerException.class, () -> new ExactProjectionReadService(null, validator));
		assertThrows(NullPointerException.class, () -> new ExactProjectionReadService(port, null));
	}

	private static ExactProjectionReadService service(ProjectionReadPort port) {
		return new ExactProjectionReadService(port, new ProjectionValidator((schema, payload) -> true));
	}

	private static ProjectionDefinition definition() {
		return new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE, List.of());
	}

	private static ProjectionDefinition definitionWithArtifact() {
		return new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE, List.of(
				new ArtifactDefinition(ARTIFACT_TYPE, new Cardinality(1, 1), JsonNull.INSTANCE)));
	}

	private static ProjectionArtifact artifact() {
		return new ProjectionArtifact(ARTIFACT_TYPE, new ArtifactKey("header"), new JsonString("payload"));
	}

	private static ProjectionKey key(long version) {
		return new ProjectionKey(PROJECTION_TYPE, TARGET_OBJECT_TYPE,
				new TargetObjectId("pot-1"), version);
	}

	private static final class RecordingProjectionReadPort implements ProjectionReadPort {
		private final Optional<Projection> projection;
		private final boolean failure;
		private int findProjectionCalls;
		private int hasFailureCalls;

		private RecordingProjectionReadPort() {
			this(Optional.empty(), false);
		}

		private RecordingProjectionReadPort(Optional<Projection> projection, boolean failure) {
			this.projection = projection;
			this.failure = failure;
		}

		@Override
		public Optional<Projection> findProjection(ProjectionKey key) {
			findProjectionCalls++;
			return projection;
		}

		@Override
		public boolean hasFailure(ProjectionKey key) {
			hasFailureCalls++;
			return failure;
		}

		private void assertNoCalls() {
			assertEquals(0, findProjectionCalls);
			assertEquals(0, hasFailureCalls);
		}
	}
}
