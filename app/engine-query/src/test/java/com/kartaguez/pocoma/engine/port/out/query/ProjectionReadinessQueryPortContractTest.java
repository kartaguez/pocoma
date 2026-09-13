package com.kartaguez.pocoma.engine.port.out.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
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
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
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
	void isolatesReadinessByPipelineVersionWithinTheSamePotAndProjectionType() {
		PotId potId = PotId.of(UUID.randomUUID());
		ProjectionGenerationIdentity first = generation("READ_POT", "read-pot", 1, potId);
		ProjectionGenerationIdentity second = generation("READ_POT", "read-pot", 2, potId);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(first, 13), ProjectionStatus.READY,
				new ProjectionIdentity(second, 15), ProjectionStatus.READY));

		assertEquals(first.projectionType(), second.projectionType());
		assertEquals(first.pipeline().pipelineId(), second.pipeline().pipelineId());
		assertEquals(first.potId(), second.potId());
		assertNotEquals(first.pipeline().pipelineVersion(), second.pipeline().pipelineVersion());
		assertEquals(OptionalLong.of(13), port.findHighestReadyBusinessVersionAtOrBelow(first, 15));
		assertEquals(OptionalLong.of(15), port.findHighestReadyBusinessVersionAtOrBelow(second, 15));
		assertEquals(ProjectionStatus.NOT_READY, port.statusAt(new ProjectionIdentity(first, 15)));
		assertEquals(ProjectionStatus.NOT_READY, port.statusAt(new ProjectionIdentity(second, 13)));
	}

	@Test
	void insertionOrderDoesNotAffectSparseReadinessLookup() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		Map<ProjectionIdentity, ProjectionStatus> ascending = new LinkedHashMap<>();
		ascending.put(new ProjectionIdentity(generation, 8), ProjectionStatus.READY);
		ascending.put(new ProjectionIdentity(generation, 12), ProjectionStatus.FAILED);
		ascending.put(new ProjectionIdentity(generation, 13), ProjectionStatus.READY);
		ascending.put(new ProjectionIdentity(generation, 15), ProjectionStatus.READY);
		Map<ProjectionIdentity, ProjectionStatus> descending = new LinkedHashMap<>();
		descending.put(new ProjectionIdentity(generation, 15), ProjectionStatus.READY);
		descending.put(new ProjectionIdentity(generation, 13), ProjectionStatus.READY);
		descending.put(new ProjectionIdentity(generation, 12), ProjectionStatus.FAILED);
		descending.put(new ProjectionIdentity(generation, 8), ProjectionStatus.READY);

		FakeProjectionReadinessQueryPort first = new FakeProjectionReadinessQueryPort(ascending);
		FakeProjectionReadinessQueryPort second = new FakeProjectionReadinessQueryPort(descending);

		assertSameSparseReadiness(first, second, generation, 15, OptionalLong.of(15));
		assertSameSparseReadiness(first, second, generation, 14, OptionalLong.of(13));
		assertSameSparseReadiness(first, second, generation, 12, OptionalLong.of(8));
		assertSameSparseReadiness(first, second, generation, 7, OptionalLong.empty());
	}

	@Test
	void fakeEnforcesDocumentedInputPreconditions() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of());

		assertThrows(NullPointerException.class,
				() -> port.findHighestReadyBusinessVersionAtOrBelow(null, 1));
		assertThrows(IllegalArgumentException.class,
				() -> port.findHighestReadyBusinessVersionAtOrBelow(generation, 0));
		assertThrows(NullPointerException.class, () -> port.statusAt(null));
	}

	private static void assertSameSparseReadiness(
			ProjectionReadinessQueryPort first,
			ProjectionReadinessQueryPort second,
			ProjectionGenerationIdentity generation,
			long bound,
			OptionalLong expected) {
		assertEquals(expected, first.findHighestReadyBusinessVersionAtOrBelow(generation, bound));
		assertEquals(expected, second.findHighestReadyBusinessVersionAtOrBelow(generation, bound));
	}

	private static ProjectionGenerationIdentity generation(
			String projectionType,
			String pipelineId,
			int version,
			PotId potId) {
		return new ProjectionGenerationIdentity(
				new ProjectionType(projectionType),
				new PipelineDefinition(new PipelineId(pipelineId), version),
				potId);
	}

	private static final class FakeProjectionReadinessQueryPort implements ProjectionReadinessQueryPort {

		private final Map<ProjectionIdentity, ProjectionStatus> statuses;

		private FakeProjectionReadinessQueryPort(Map<ProjectionIdentity, ProjectionStatus> statuses) {
			this.statuses = new LinkedHashMap<>(statuses);
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
