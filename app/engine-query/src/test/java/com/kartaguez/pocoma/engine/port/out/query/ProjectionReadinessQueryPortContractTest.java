package com.kartaguez.pocoma.engine.port.out.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
	void findsHighestActualTerminalAtOrBelowBoundWithoutAssumingContinuity() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(generation, 16), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 15), ProjectionStatus.READY,
				new ProjectionIdentity(generation, 13), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 12), ProjectionStatus.NOT_READY,
				new ProjectionIdentity(generation, 8), ProjectionStatus.READY));

		assertEquals(terminal(15, ProjectionStatus.READY), port.findHighestTerminalAtOrBelow(generation, 15));
		assertEquals(terminal(13, ProjectionStatus.FAILED), port.findHighestTerminalAtOrBelow(generation, 14));
		assertEquals(terminal(8, ProjectionStatus.READY), port.findHighestTerminalAtOrBelow(generation, 12));
		assertEquals(Optional.empty(), port.findHighestTerminalAtOrBelow(generation, 7));
		assertEquals(ProjectionStatus.NOT_READY, port.statusAt(new ProjectionIdentity(generation, 14)));
		assertEquals(ProjectionStatus.NOT_READY, port.statusAt(new ProjectionIdentity(generation, 12)));
	}

	@Test
	void returnsFailedAtTheBoundInsteadOfFallingBackToOlderReady() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(generation, 15), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 13), ProjectionStatus.READY));

		assertEquals(terminal(15, ProjectionStatus.FAILED), port.findHighestTerminalAtOrBelow(generation, 15));
	}

	@Test
	void neverFallsBackFromTheServingPipelineVersionToAnOlderGeneration() {
		PotId potId = PotId.of(UUID.randomUUID());
		ProjectionGenerationIdentity oldGeneration = generation("READ_POT", "read-pot", 2, potId);
		ProjectionGenerationIdentity servingGeneration = generation("READ_POT", "read-pot", 3, potId);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(oldGeneration, 15), ProjectionStatus.READY,
				new ProjectionIdentity(servingGeneration, 13), ProjectionStatus.READY));

		assertEquals(terminal(13, ProjectionStatus.READY),
				port.findHighestTerminalAtOrBelow(servingGeneration, 15));
		assertEquals(terminal(15, ProjectionStatus.READY),
				port.findHighestTerminalAtOrBelow(oldGeneration, 15));
	}

	@Test
	void returnsEmptyForServingGenerationEvenWhenAnOlderGenerationIsReady() {
		PotId potId = PotId.of(UUID.randomUUID());
		ProjectionGenerationIdentity oldGeneration = generation("READ_POT", "read-pot", 2, potId);
		ProjectionGenerationIdentity servingGeneration = generation("READ_POT", "read-pot", 3, potId);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(oldGeneration, 15), ProjectionStatus.READY,
				new ProjectionIdentity(oldGeneration, 13), ProjectionStatus.READY));

		assertEquals(Optional.empty(), port.findHighestTerminalAtOrBelow(servingGeneration, 15));
	}

	@Test
	void isolatesTerminalStatesByTheCompleteGenerationIdentity() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotId otherPotId = PotId.of(UUID.randomUUID());
		ProjectionGenerationIdentity base = generation("READ_POT", "read-pot", 1, potId);
		ProjectionGenerationIdentity otherVersion = generation("READ_POT", "read-pot", 2, potId);
		ProjectionGenerationIdentity otherPipeline = generation("READ_POT", "other-read-pot", 1, potId);
		ProjectionGenerationIdentity otherType = generation("BALANCE", "read-pot", 1, potId);
		ProjectionGenerationIdentity otherPot = generation("READ_POT", "read-pot", 1, otherPotId);
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of(
				new ProjectionIdentity(base, 11), ProjectionStatus.READY,
				new ProjectionIdentity(otherVersion, 12), ProjectionStatus.FAILED,
				new ProjectionIdentity(otherPipeline, 13), ProjectionStatus.READY,
				new ProjectionIdentity(otherType, 14), ProjectionStatus.FAILED,
				new ProjectionIdentity(otherPot, 15), ProjectionStatus.READY));

		assertNotEquals(base, otherVersion);
		assertEquals(terminal(11, ProjectionStatus.READY), port.findHighestTerminalAtOrBelow(base, 20));
		assertEquals(terminal(12, ProjectionStatus.FAILED), port.findHighestTerminalAtOrBelow(otherVersion, 20));
		assertEquals(terminal(13, ProjectionStatus.READY), port.findHighestTerminalAtOrBelow(otherPipeline, 20));
		assertEquals(terminal(14, ProjectionStatus.FAILED), port.findHighestTerminalAtOrBelow(otherType, 20));
		assertEquals(terminal(15, ProjectionStatus.READY), port.findHighestTerminalAtOrBelow(otherPot, 20));
	}

	@Test
	void insertionOrderDoesNotAffectSparseTerminalLookup() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		Map<ProjectionIdentity, ProjectionStatus> ascending = new LinkedHashMap<>();
		ascending.put(new ProjectionIdentity(generation, 8), ProjectionStatus.READY);
		ascending.put(new ProjectionIdentity(generation, 12), ProjectionStatus.NOT_READY);
		ascending.put(new ProjectionIdentity(generation, 13), ProjectionStatus.FAILED);
		ascending.put(new ProjectionIdentity(generation, 15), ProjectionStatus.READY);
		Map<ProjectionIdentity, ProjectionStatus> descending = new LinkedHashMap<>();
		descending.put(new ProjectionIdentity(generation, 15), ProjectionStatus.READY);
		descending.put(new ProjectionIdentity(generation, 13), ProjectionStatus.FAILED);
		descending.put(new ProjectionIdentity(generation, 12), ProjectionStatus.NOT_READY);
		descending.put(new ProjectionIdentity(generation, 8), ProjectionStatus.READY);

		FakeProjectionReadinessQueryPort first = new FakeProjectionReadinessQueryPort(ascending);
		FakeProjectionReadinessQueryPort second = new FakeProjectionReadinessQueryPort(descending);

		assertSameTerminal(first, second, generation, 15, terminal(15, ProjectionStatus.READY));
		assertSameTerminal(first, second, generation, 14, terminal(13, ProjectionStatus.FAILED));
		assertSameTerminal(first, second, generation, 12, terminal(8, ProjectionStatus.READY));
		assertSameTerminal(first, second, generation, 7, Optional.empty());
	}

	@Test
	void fakeEnforcesDocumentedInputPreconditions() {
		ProjectionGenerationIdentity generation = generation(
				"READ_POT", "read-pot", 1, PotId.of(UUID.randomUUID()));
		FakeProjectionReadinessQueryPort port = new FakeProjectionReadinessQueryPort(Map.of());

		assertThrows(NullPointerException.class, () -> port.findHighestTerminalAtOrBelow(null, 1));
		assertThrows(IllegalArgumentException.class, () -> port.findHighestTerminalAtOrBelow(generation, 0));
		assertThrows(NullPointerException.class, () -> port.statusAt(null));
	}

	private static Optional<TerminalProjectionState> terminal(long version, ProjectionStatus status) {
		return Optional.of(new TerminalProjectionState(version, status));
	}

	private static void assertSameTerminal(
			ProjectionReadinessQueryPort first,
			ProjectionReadinessQueryPort second,
			ProjectionGenerationIdentity generation,
			long bound,
			Optional<TerminalProjectionState> expected) {
		assertEquals(expected, first.findHighestTerminalAtOrBelow(generation, bound));
		assertEquals(expected, second.findHighestTerminalAtOrBelow(generation, bound));
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
		public Optional<TerminalProjectionState> findHighestTerminalAtOrBelow(
				ProjectionGenerationIdentity generation,
				long upperBoundInclusive) {
			java.util.Objects.requireNonNull(generation, "generation must not be null");
			if (upperBoundInclusive < 1) {
				throw new IllegalArgumentException("upperBoundInclusive must be greater than or equal to 1");
			}
			return statuses.entrySet().stream()
					.filter(entry -> entry.getKey().generation().equals(generation))
					.filter(entry -> entry.getValue() == ProjectionStatus.READY
							|| entry.getValue() == ProjectionStatus.FAILED)
					.filter(entry -> entry.getKey().potVersion() <= upperBoundInclusive)
					.max(Map.Entry.comparingByKey(Comparator.comparingLong(ProjectionIdentity::potVersion)))
					.map(entry -> new TerminalProjectionState(entry.getKey().potVersion(), entry.getValue()));
		}

		@Override
		public ProjectionStatus statusAt(ProjectionIdentity identity) {
			java.util.Objects.requireNonNull(identity, "identity must not be null");
			return statuses.getOrDefault(identity, ProjectionStatus.NOT_READY);
		}
	}
}
