package com.kartaguez.pocoma.engine.port.out.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class ProjectionReadinessQueryPortContractTest {

	@Test
	void findsHighestActualReadyVersionAtOrBelowBoundWithoutAssumingContinuity() {
		ProjectionGenerationIdentity generation = generation("READ_POT", "read-pot", 1);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(generation, 15), ProjectionStatus.READY,
				new ProjectionIdentity(generation, 13), ProjectionStatus.READY,
				new ProjectionIdentity(generation, 12), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 8), ProjectionStatus.READY));

		assertEquals(OptionalLong.of(15), port.findHighestReadyBusinessVersionAtOrBelow(generation, 15));
		assertEquals(OptionalLong.of(13), port.findHighestReadyBusinessVersionAtOrBelow(generation, 14));
		assertEquals(OptionalLong.of(8), port.findHighestReadyBusinessVersionAtOrBelow(generation, 12));
		assertEquals(OptionalLong.empty(), port.findHighestReadyBusinessVersionAtOrBelow(generation, 7));
		assertEquals(ProjectionStatus.NOT_READY, port.statusAt(new ProjectionIdentity(generation, 14)));
		assertEquals(ProjectionStatus.FAILED, port.statusAt(new ProjectionIdentity(generation, 12)));
	}

	@Test
	void isolatesReadinessByProjectionGeneration() {
		ProjectionGenerationIdentity first = generation("READ_POT", "read-pot", 1);
		ProjectionGenerationIdentity second = generation("READ_POT", "read-pot", 2);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(first, 13), ProjectionStatus.READY,
				new ProjectionIdentity(second, 11), ProjectionStatus.READY));

		assertEquals(OptionalLong.of(13), port.findHighestReadyBusinessVersionAtOrBelow(first, 15));
		assertEquals(OptionalLong.of(11), port.findHighestReadyBusinessVersionAtOrBelow(second, 15));
	}

	@Test
	void fakeEnforcesDocumentedInputPreconditions() {
		ProjectionGenerationIdentity generation = generation("READ_POT", "read-pot", 1);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of());

		assertThrows(NullPointerException.class,
				() -> port.findHighestReadyBusinessVersionAtOrBelow(null, 1));
		assertThrows(IllegalArgumentException.class,
				() -> port.findHighestReadyBusinessVersionAtOrBelow(generation, 0));
		assertThrows(NullPointerException.class, () -> port.statusAt(null));
	}

	private static ProjectionGenerationIdentity generation(String projectionType, String pipelineId, int version) {
		return new ProjectionGenerationIdentity(
				new ProjectionType(projectionType),
				new PipelineDefinition(new PipelineId(pipelineId), version),
				PotId.of(UUID.randomUUID()));
	}

	private static final class FakeProjectionReadinessQueryPort implements ProjectionReadinessQueryPort {

		private final Map<ProjectionIdentity, ProjectionStatus> statuses;

		private FakeProjectionReadinessQueryPort(Map<ProjectionIdentity, ProjectionStatus> statuses) {
			this.statuses = new HashMap<>(statuses);
		}

		@Override
		public OptionalLong findHighestReadyBusinessVersionAtOrBelow(
				ProjectionGenerationIdentity generation,
				long upperBoundInclusive) {
			java.util.Objects.requireNonNull(generation, "generation must not be null");
			if (upperBoundInclusive < 1) {
				throw new IllegalArgumentException("upperBoundInclusive must be greater than or equal to 1");
			}
			return statuses.entrySet().stream()
					.filter(entry -> entry.getKey().generation().equals(generation))
					.filter(entry -> entry.getValue() == ProjectionStatus.READY)
					.mapToLong(entry -> entry.getKey().potVersion())
					.filter(version -> version <= upperBoundInclusive)
					.max();
		}

		@Override
		public ProjectionStatus statusAt(ProjectionIdentity identity) {
			java.util.Objects.requireNonNull(identity, "identity must not be null");
			return statuses.getOrDefault(identity, ProjectionStatus.NOT_READY);
		}
	}
}
