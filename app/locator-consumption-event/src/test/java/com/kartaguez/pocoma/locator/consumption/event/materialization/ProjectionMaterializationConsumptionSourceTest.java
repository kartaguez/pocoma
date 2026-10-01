package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class ProjectionMaterializationConsumptionSourceTest {
	private static final UUID EVENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID EVENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final EventType EVENT_TYPE = new EventType("POT_CREATED");
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType POT_BALANCES = new ProjectionType("POT_BALANCES");
	private static final TargetObjectType POT = new TargetObjectType("POT");
	private static final TargetObjectId POT_ID = new TargetObjectId("20000000-0000-0000-0000-000000000001");
	private static final Instant RECORDED_AT = Instant.parse("2026-09-26T10:00:00Z");

	@Test
	void mapsExactlyEventIdAndProjectionTypeToTheConsumptionKey() {
		var candidate = candidate(EVENT_A, READ_POT, 42);

		assertEquals(new ConsumptionKey(
				new ConsumableIdentity("EVENT", List.of(EVENT_A.toString())),
				new ConsumerIdentity("PROJECTION_TASK_MATERIALIZER", List.of("READ_POT"))),
				ProjectionMaterializationConsumptionKeys.consumptionKey(candidate));
	}

	@Test
	void consequencesRemainIndependentByEventAndProjectionTypeOnly() {
		var eventRead = ProjectionMaterializationConsumptionKeys.consumptionKey(candidate(EVENT_A, READ_POT, 42));
		var eventBalances = ProjectionMaterializationConsumptionKeys.consumptionKey(
				candidate(EVENT_A, POT_BALANCES, 42));
		var otherEventRead = ProjectionMaterializationConsumptionKeys.consumptionKey(
				candidate(EVENT_B, READ_POT, 42));
		var metadataChanged = ProjectionMaterializationConsumptionKeys.consumptionKey(
				new ProjectionMaterializationCandidate(EVENT_A, new EventType("EXPENSE_ADDED"), READ_POT,
						new TargetObjectType("OTHER"), new TargetObjectId("other"), 99, RECORDED_AT.plusSeconds(1)));

		assertNotEquals(eventRead, eventBalances);
		assertNotEquals(eventRead, otherEventRead);
		assertEquals(eventRead, metadataChanged);
	}

	@Test
	void pagesWithTheCandidateOrderingKeyAndKeepsTheCandidateIntact() {
		var first = candidate(EVENT_A, READ_POT, 1);
		var second = candidate(EVENT_A, POT_BALANCES, 1);
		var discovery = new RecordingDiscovery(List.of(List.of(first), List.of(second), List.of()));
		var routes = Map.of(EVENT_TYPE, Set.of(READ_POT, POT_BALANCES));
		var segment = new WorkerSegment(2, 4);
		var source = new ProjectionMaterializationConsumptionSource(routes, segment, discovery);
		var search = source.openSearch();

		assertSame(first, search.nextPage(3).getFirst());
		assertSame(second, search.nextPage(2).getFirst());
		assertEquals(List.of(), search.nextPage(1));

		assertEquals(List.of(Optional.empty(), Optional.of(first.orderingKey()), Optional.of(second.orderingKey())),
				discovery.cursors);
		assertEquals(List.of(3, 2, 1), discovery.limits);
		assertEquals(List.of(routes, routes, routes), discovery.routes);
		assertEquals(List.of(segment, segment, segment), discovery.segments);
	}

	@Test
	void rejectsInvalidInputsWithoutCreatingAuthority() {
		assertThrows(NullPointerException.class, () -> ProjectionMaterializationConsumptionKeys.consumptionKey(null));
		var source = new ProjectionMaterializationConsumptionSource(
				Map.of(EVENT_TYPE, Set.of(READ_POT)), WorkerSegment.single(),
				(routes, segment, cursor, limit) -> List.of());
		assertThrows(IllegalArgumentException.class, () -> source.openSearch().nextPage(0));
	}

	private static ProjectionMaterializationCandidate candidate(UUID eventId, ProjectionType projectionType, long version) {
		return new ProjectionMaterializationCandidate(
				eventId, EVENT_TYPE, projectionType, POT, POT_ID, version, RECORDED_AT);
	}

	private static final class RecordingDiscovery implements ProjectionMaterializationDiscoveryPort {
		private final ArrayDeque<List<ProjectionMaterializationCandidate>> pages;
		private final List<Map<EventType, Set<ProjectionType>>> routes = new ArrayList<>();
		private final List<WorkerSegment> segments = new ArrayList<>();
		private final List<Optional<ProjectionMaterializationOrderingKey>> cursors = new ArrayList<>();
		private final List<Integer> limits = new ArrayList<>();

		private RecordingDiscovery(List<List<ProjectionMaterializationCandidate>> pages) {
			this.pages = new ArrayDeque<>(pages);
		}

		@Override
		public List<ProjectionMaterializationCandidate> findCandidates(
				Map<EventType, Set<ProjectionType>> actualRoutes, WorkerSegment segment,
				Optional<ProjectionMaterializationOrderingKey> cursor, int limit) {
			routes.add(actualRoutes); segments.add(segment); cursors.add(cursor); limits.add(limit);
			return pages.remove();
		}
	}
}
