package com.kartaguez.pocoma.engine.service.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.legacy.LatestKnownVersion;
import com.kartaguez.pocoma.domain.projection.legacy.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.legacy.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.legacy.ProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryProjectionSelection;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionIntent;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionResolution;
import com.kartaguez.pocoma.engine.port.out.query.LatestKnownVersionQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.ProjectionReadinessQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.TerminalProjectionState;

class QueryVersionResolverTest {

	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final PipelineId PIPELINE_ID = new PipelineId("read-pot");

	@Test
	void currentWithoutLatestKnownIsNotReadyWithoutReadinessLookup() {
		PotId potId = potId();
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of());
		QueryVersionResolver resolver = resolver(Optional.empty(), readiness);

		QueryVersionResolution result = resolver.resolve(potId, QueryVersionIntent.current(), selection(3));

		assertEquals(
				new QueryVersionResolution.NotReady(potId, QueryVersionIntent.current(), OptionalLong.empty()),
				result);
		assertEquals(0, readiness.terminalCalls);
		assertEquals(0, readiness.statusCalls);
	}

	@Test
	void currentWithoutTerminalIsNotReadyEvenWhenAnOlderGenerationIsReady() {
		PotId potId = potId();
		QueryProjectionSelection serving = selection(3);
		ProjectionGenerationIdentity oldGeneration = generation(potId, selection(2));
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(oldGeneration, 15), ProjectionStatus.READY));

		QueryVersionResolution result = resolver(latest(potId, 15), readiness)
				.resolve(potId, QueryVersionIntent.current(), serving);

		assertEquals(
				new QueryVersionResolution.NotReady(potId, QueryVersionIntent.current(), OptionalLong.of(15)),
				result);
		assertEquals(generation(potId, serving), readiness.lastTerminalGeneration);
		assertEquals(1, readiness.terminalCalls);
		assertEquals(0, readiness.statusCalls);
	}

	@Test
	void currentResolvesHighestReadyTerminalWithinLatestKnown() {
		PotId potId = potId();
		QueryProjectionSelection selection = selection(3);
		ProjectionGenerationIdentity generation = generation(potId, selection);
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(generation, 16), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 15), ProjectionStatus.NOT_READY,
				new ProjectionIdentity(generation, 13), ProjectionStatus.READY));

		QueryVersionResolution result = resolver(latest(potId, 15), readiness)
				.resolve(potId, QueryVersionIntent.current(), selection);

		assertEquals(new QueryVersionResolution.Resolved(potId, QueryVersionIntent.current(), 13, 15), result);
		assertEquals(15, readiness.lastTerminalBound);
		assertEquals(0, readiness.statusCalls);
	}

	@Test
	void currentMapsReadyAndFailedAtTheLatestKnownBound() {
		PotId potId = potId();
		QueryProjectionSelection selection = selection(3);
		ProjectionGenerationIdentity generation = generation(potId, selection);
		RecordingReadinessPort ready = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(generation, 15), ProjectionStatus.READY));
		RecordingReadinessPort failed = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(generation, 15), ProjectionStatus.FAILED));

		assertEquals(
				new QueryVersionResolution.Resolved(potId, QueryVersionIntent.current(), 15, 15),
				resolver(latest(potId, 15), ready).resolve(potId, QueryVersionIntent.current(), selection));
		assertEquals(
				new QueryVersionResolution.ProjectionFailed(potId, QueryVersionIntent.current(), 15, 15),
				resolver(latest(potId, 15), failed).resolve(potId, QueryVersionIntent.current(), selection));
	}

	@Test
	void currentUsesTheServingTerminalInsteadOfANewerReadyFromAnOldGeneration() {
		PotId potId = potId();
		QueryProjectionSelection serving = selection(3);
		ProjectionGenerationIdentity servingGeneration = generation(potId, serving);
		ProjectionGenerationIdentity oldGeneration = generation(potId, selection(2));
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(servingGeneration, 98), ProjectionStatus.READY,
				new ProjectionIdentity(oldGeneration, 100), ProjectionStatus.READY));

		QueryVersionResolution result = resolver(latest(potId, 100), readiness)
				.resolve(potId, QueryVersionIntent.current(), serving);

		assertEquals(
				new QueryVersionResolution.Resolved(potId, QueryVersionIntent.current(), 98, 100),
				result);
		assertEquals(servingGeneration, readiness.lastTerminalGeneration);
	}

	@Test
	void currentReturnsRecentFailedTerminalInsteadOfOlderReady() {
		PotId potId = potId();
		QueryProjectionSelection selection = selection(3);
		ProjectionGenerationIdentity generation = generation(potId, selection);
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(generation, 15), ProjectionStatus.NOT_READY,
				new ProjectionIdentity(generation, 14), ProjectionStatus.FAILED,
				new ProjectionIdentity(generation, 13), ProjectionStatus.READY));

		QueryVersionResolution result = resolver(latest(potId, 15), readiness)
				.resolve(potId, QueryVersionIntent.current(), selection);

		assertEquals(
				new QueryVersionResolution.ProjectionFailed(potId, QueryVersionIntent.current(), 14, 15),
				result);
	}

	@Test
	void exactWithoutLatestKnownIsNotReadyWithoutReadinessLookup() {
		PotId potId = potId();
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of());
		QueryVersionIntent.Exact exact = QueryVersionIntent.exact(15);

		QueryVersionResolution result = resolver(Optional.empty(), readiness).resolve(potId, exact, selection(3));

		assertEquals(new QueryVersionResolution.NotReady(potId, exact, OptionalLong.empty()), result);
		assertEquals(0, readiness.terminalCalls);
		assertEquals(0, readiness.statusCalls);
	}

	@Test
	void exactAboveLatestKnownIsNotReadyBeforeApplicabilityOrStatus() {
		PotId potId = potId();
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of());
		QueryVersionIntent.Exact exact = QueryVersionIntent.exact(15);
		QueryProjectionSelection notApplicable = selection(3, VersionApplicability.between(1, 14));

		QueryVersionResolution result = resolver(latest(potId, 14), readiness)
				.resolve(potId, exact, notApplicable);

		assertEquals(new QueryVersionResolution.NotReady(potId, exact, OptionalLong.of(14)), result);
		assertEquals(0, readiness.statusCalls);
		assertEquals(0, readiness.terminalCalls);
	}

	@Test
	void exactOutsideServingApplicabilityIsNotApplicableWithoutStatusLookup() {
		PotId potId = potId();
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of());
		QueryVersionIntent.Exact exact = QueryVersionIntent.exact(15);

		QueryVersionResolution result = resolver(latest(potId, 15), readiness)
				.resolve(potId, exact, selection(3, VersionApplicability.from(16)));

		assertEquals(new QueryVersionResolution.NotApplicable(potId, exact, 15), result);
		assertEquals(0, readiness.statusCalls);
		assertEquals(0, readiness.terminalCalls);
	}

	@Test
	void exactMapsReadyFailedAndNotReadyWithoutFallback() {
		PotId potId = potId();
		QueryProjectionSelection serving = selection(3);
		ProjectionGenerationIdentity servingGeneration = generation(potId, serving);
		ProjectionGenerationIdentity oldGeneration = generation(potId, selection(2));
		QueryVersionIntent.Exact exactReady = QueryVersionIntent.exact(15);
		QueryVersionIntent.Exact exactFailed = QueryVersionIntent.exact(14);
		QueryVersionIntent.Exact exactNotReady = QueryVersionIntent.exact(13);
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of(
				new ProjectionIdentity(servingGeneration, 15), ProjectionStatus.READY,
				new ProjectionIdentity(servingGeneration, 14), ProjectionStatus.FAILED,
				new ProjectionIdentity(oldGeneration, 13), ProjectionStatus.READY));
		QueryVersionResolver resolver = resolver(latest(potId, 15), readiness);

		assertEquals(new QueryVersionResolution.Resolved(potId, exactReady, 15, 15),
				resolver.resolve(potId, exactReady, serving));
		assertEquals(new QueryVersionResolution.ProjectionFailed(potId, exactFailed, 14, 15),
				resolver.resolve(potId, exactFailed, serving));
		assertEquals(new QueryVersionResolution.NotReady(potId, exactNotReady, OptionalLong.of(15)),
				resolver.resolve(potId, exactNotReady, serving));
		assertEquals(new ProjectionIdentity(servingGeneration, 13), readiness.lastStatusIdentity);
		assertEquals(0, readiness.terminalCalls);
	}

	@Test
	void validatesConstructorAndResolveInputsBeforeCallingPorts() {
		RecordingReadinessPort readiness = new RecordingReadinessPort(Map.of());
		LatestKnownVersionQueryPort latest = potId -> Optional.empty();
		assertThrows(NullPointerException.class, () -> new QueryVersionResolver(null, readiness));
		assertThrows(NullPointerException.class, () -> new QueryVersionResolver(latest, null));

		QueryVersionResolver resolver = new QueryVersionResolver(latest, readiness);
		assertThrows(NullPointerException.class,
				() -> resolver.resolve(null, QueryVersionIntent.current(), selection(1)));
		assertThrows(NullPointerException.class,
				() -> resolver.resolve(potId(), null, selection(1)));
		assertThrows(NullPointerException.class,
				() -> resolver.resolve(potId(), QueryVersionIntent.current(), null));
	}

	@Test
	void exposesOnePublicResolutionMethodAndOnlyTwoPortDependencies() {
		assertEquals(
				java.util.List.of("resolve"),
				Arrays.stream(QueryVersionResolver.class.getDeclaredMethods())
						.filter(method -> Modifier.isPublic(method.getModifiers()))
						.map(java.lang.reflect.Method::getName)
						.sorted()
						.toList());
		assertEquals(
				java.util.List.of(LatestKnownVersionQueryPort.class, ProjectionReadinessQueryPort.class),
				Arrays.asList(QueryVersionResolver.class.getConstructors()[0].getParameterTypes()));
	}

	private static QueryVersionResolver resolver(
			Optional<LatestKnownVersion> latestKnown,
			ProjectionReadinessQueryPort readiness) {
		return new QueryVersionResolver(potId -> latestKnown, readiness);
	}

	private static Optional<LatestKnownVersion> latest(PotId potId, long version) {
		return Optional.of(new LatestKnownVersion(potId, version));
	}

	private static QueryProjectionSelection selection(int pipelineVersion) {
		return selection(pipelineVersion, VersionApplicability.from(1));
	}

	private static QueryProjectionSelection selection(
			int pipelineVersion,
			VersionApplicability applicability) {
		return new QueryProjectionSelection(
				READ_POT,
				new PipelineVersionDefinition(
						new PipelineDefinition(PIPELINE_ID, pipelineVersion),
						applicability));
	}

	private static ProjectionGenerationIdentity generation(PotId potId, QueryProjectionSelection selection) {
		return new ProjectionGenerationIdentity(
				selection.projectionType(), selection.servingPipeline().identity(), potId);
	}

	private static PotId potId() {
		return PotId.of(UUID.randomUUID());
	}

	private static final class RecordingReadinessPort implements ProjectionReadinessQueryPort {

		private final Map<ProjectionIdentity, ProjectionStatus> statuses;
		private int terminalCalls;
		private int statusCalls;
		private ProjectionGenerationIdentity lastTerminalGeneration;
		private long lastTerminalBound;
		private ProjectionIdentity lastStatusIdentity;

		private RecordingReadinessPort(Map<ProjectionIdentity, ProjectionStatus> statuses) {
			this.statuses = new LinkedHashMap<>(statuses);
		}

		@Override
		public Optional<TerminalProjectionState> findHighestTerminalAtOrBelow(
				ProjectionGenerationIdentity generation,
				long upperBoundInclusive) {
			terminalCalls++;
			lastTerminalGeneration = generation;
			lastTerminalBound = upperBoundInclusive;
			return statuses.entrySet().stream()
					.filter(entry -> entry.getKey().generation().equals(generation))
					.filter(entry -> entry.getKey().potVersion() <= upperBoundInclusive)
					.filter(entry -> entry.getValue() == ProjectionStatus.READY
							|| entry.getValue() == ProjectionStatus.FAILED)
					.max(Map.Entry.comparingByKey(Comparator.comparingLong(ProjectionIdentity::potVersion)))
					.map(entry -> new TerminalProjectionState(entry.getKey().potVersion(), entry.getValue()));
		}

		@Override
		public ProjectionStatus statusAt(ProjectionIdentity identity) {
			statusCalls++;
			lastStatusIdentity = identity;
			return statuses.getOrDefault(identity, ProjectionStatus.NOT_READY);
		}
	}
}
