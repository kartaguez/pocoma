package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Acquired;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JdbcProjectionMaterializationDiscoveryAdapterPostgresTest {
	private static final EventType POT_CREATED = new EventType("POT_CREATED");
	private static final EventType POT_DELETED = new EventType("POT_DELETED");
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType POT_BALANCES = new ProjectionType("POT_BALANCES");
	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofMinutes(1));
	private static final String UNPARSEABLE_PAYLOAD = "not JSON and not a serialized BusinessEvent";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private JdbcProjectionMaterializationDiscoveryAdapter discovery;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("update consumption_slots set current_claim_id = null");
		jdbc.update("delete from consumption_claims");
		jdbc.update("delete from consumption_slots");
		jdbc.update("delete from tasks_4_pipeline");
		jdbc.update("delete from business_event_outbox");
	}

	@Test
	void discoversCanonicalMetadataWithoutInterpretingTheBusinessPayload() {
		UUID eventId = uuid(1);
		UUID potId = uuid(101);
		insertEvent(eventId, POT_CREATED, potId, 7, NOW, 0, UNPARSEABLE_PAYLOAD);

		List<ProjectionMaterializationCandidate> candidates = discovery.findCandidates(
				routes(POT_CREATED, READ_POT), WorkerSegment.single(), Optional.empty(), 10);

		assertEquals(1, candidates.size());
		var candidate = candidates.getFirst();
		assertEquals(eventId, candidate.eventId());
		assertEquals(POT_CREATED, candidate.eventType());
		assertEquals(READ_POT, candidate.projectionType());
		assertEquals(new TargetObjectType("POT"), candidate.targetObjectType());
		assertEquals(new TargetObjectId(potId.toString()), candidate.targetObjectId());
		assertEquals(7, candidate.targetVersion());
		assertEquals(NOW, candidate.recordedAt());

		assertTrue(discovery.findCandidates(
				routes(POT_DELETED, READ_POT), WorkerSegment.single(), Optional.empty(), 10).isEmpty());
		assertTrue(discovery.findCandidates(
				Map.of(), WorkerSegment.single(), Optional.empty(), 10).isEmpty());
	}

	@Test
	void expandsOnlyTheSuppliedRoutes() {
		insertEvent(uuid(2), POT_CREATED, uuid(102), 1, NOW, 0, UNPARSEABLE_PAYLOAD);

		assertEquals(List.of(READ_POT), discovery.findCandidates(
				routes(POT_CREATED, READ_POT), WorkerSegment.single(), Optional.empty(), 10).stream()
				.map(ProjectionMaterializationCandidate::projectionType).toList());
		assertEquals(List.of(POT_BALANCES, READ_POT), discovery.findCandidates(
				Map.of(POT_CREATED, Set.of(READ_POT, POT_BALANCES)),
				WorkerSegment.single(), Optional.empty(), 10).stream()
				.map(ProjectionMaterializationCandidate::projectionType).toList());
	}

	@Test
	void aNewRouteBackfillsAnHistoricalEventWithoutDurableCursorOrWatermark() {
		UUID historicalEvent = uuid(3);
		insertEvent(historicalEvent, POT_CREATED, uuid(103), 1, NOW, 0, UNPARSEABLE_PAYLOAD);

		Map<EventType, Set<ProjectionType>> initialRoutes = routes(POT_CREATED, READ_POT);
		assertEquals(List.of(new ProjectionMaterializationOrderingKey(NOW, historicalEvent, READ_POT)),
				scan(initialRoutes, 1));
		done(historicalEvent, READ_POT);
		assertTrue(discovery.findCandidates(
				initialRoutes, WorkerSegment.single(), Optional.empty(), 1).isEmpty());

		Map<EventType, Set<ProjectionType>> enrichedRoutes =
				Map.of(POT_CREATED, Set.of(READ_POT, POT_BALANCES));
		assertEquals(List.of(new ProjectionMaterializationOrderingKey(NOW, historicalEvent, POT_BALANCES)),
				scan(enrichedRoutes, 1));
	}

	@Test
	void excludesOnlyTheExactDoneConsumption() {
		UUID noSlot = uuid(10);
		UUID busy = uuid(11);
		UUID notReady = uuid(12);
		UUID doneOtherProjection = uuid(13);
		UUID doneExact = uuid(14);
		for (UUID eventId : List.of(noSlot, busy, notReady, doneOtherProjection, doneExact)) {
			insertEvent(eventId, POT_CREATED, UUID.randomUUID(), 1, NOW, 0, UNPARSEABLE_PAYLOAD);
		}

		acquire(busy, READ_POT);
		Claim delayed = acquire(notReady, READ_POT);
		inTransaction(() -> lifecycle.handleFailure(delayed.slotId(), delayed.claimId(),
				new ProcessingFailure(new ProcessingFailureCode("TEMPORARY"), "TEMPORARY", "retry", NOW),
				new RetryAfter(Duration.ofHours(1)), NOW));
		done(doneOtherProjection, POT_BALANCES);
		done(doneExact, READ_POT);

		assertEquals(Set.of(noSlot, busy, notReady, doneOtherProjection), discovery.findCandidates(
				routes(POT_CREATED, READ_POT), WorkerSegment.single(), Optional.empty(), 10).stream()
				.map(ProjectionMaterializationCandidate::eventId).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void keepsEveryProjectionInsideTheEventPotSegment() {
		UUID inside = uuid(20);
		UUID outside = uuid(21);
		insertEvent(inside, POT_CREATED, uuid(120), 1, NOW, -2, UNPARSEABLE_PAYLOAD);
		insertEvent(outside, POT_CREATED, uuid(121), 1, NOW, -1, UNPARSEABLE_PAYLOAD);

		assertEquals(List.of(inside, inside), discovery.findCandidates(
				Map.of(POT_CREATED, Set.of(READ_POT, POT_BALANCES)),
				new WorkerSegment(0, 2), Optional.empty(), 10).stream()
				.map(ProjectionMaterializationCandidate::eventId).toList());
	}

	@Test
	void keysetPaginationTraversesExpandedRowsWithoutDuplicatesOrGaps() {
		UUID first = uuid(30);
		UUID second = uuid(31);
		UUID later = uuid(32);
		insertEvent(first, POT_CREATED, uuid(130), 1, NOW, 0, UNPARSEABLE_PAYLOAD);
		insertEvent(second, POT_CREATED, uuid(131), 1, NOW, 0, UNPARSEABLE_PAYLOAD);
		insertEvent(later, POT_CREATED, uuid(132), 1, NOW.plusSeconds(1), 0, UNPARSEABLE_PAYLOAD);

		Map<EventType, Set<ProjectionType>> routes = Map.of(POT_CREATED, Set.of(READ_POT, POT_BALANCES));
		List<ProjectionMaterializationOrderingKey> keys = scan(routes, 1);

		assertEquals(List.of(
				new ProjectionMaterializationOrderingKey(NOW, first, POT_BALANCES),
				new ProjectionMaterializationOrderingKey(NOW, first, READ_POT),
				new ProjectionMaterializationOrderingKey(NOW, second, POT_BALANCES),
				new ProjectionMaterializationOrderingKey(NOW, second, READ_POT),
				new ProjectionMaterializationOrderingKey(NOW.plusSeconds(1), later, POT_BALANCES),
				new ProjectionMaterializationOrderingKey(NOW.plusSeconds(1), later, READ_POT)), keys);
		assertEquals(keys.size(), Set.copyOf(keys).size());
	}

	@Test
	void aNewScanRestartsFromTheBeginningAndObservesNewlyDoneConsumptions() {
		UUID first = uuid(40);
		UUID second = uuid(41);
		insertEvent(first, POT_CREATED, uuid(140), 1, NOW, 0, UNPARSEABLE_PAYLOAD);
		insertEvent(second, POT_CREATED, uuid(141), 1, NOW.plusSeconds(1), 0, UNPARSEABLE_PAYLOAD);
		Map<EventType, Set<ProjectionType>> routes = routes(POT_CREATED, READ_POT);

		var firstPage = discovery.findCandidates(routes, WorkerSegment.single(), Optional.empty(), 1);
		assertEquals(first, firstPage.getFirst().eventId());
		done(second, READ_POT);
		assertTrue(discovery.findCandidates(routes, WorkerSegment.single(),
				Optional.of(firstPage.getFirst().orderingKey()), 1).isEmpty());
		assertEquals(first, discovery.findCandidates(
				routes, WorkerSegment.single(), Optional.empty(), 1).getFirst().eventId());
		done(first, READ_POT);
		assertTrue(discovery.findCandidates(
				routes, WorkerSegment.single(), Optional.empty(), 1).isEmpty());
	}

	@Test
	void rejectsInvalidArgumentsAndRouteContents() {
		assertThrows(IllegalArgumentException.class, () -> discovery.findCandidates(
				Map.of(), WorkerSegment.single(), Optional.empty(), 0));
		var nullEventType = new HashMap<EventType, Set<ProjectionType>>();
		nullEventType.put(null, Set.of(READ_POT));
		assertThrows(NullPointerException.class, () -> discovery.findCandidates(
				nullEventType, WorkerSegment.single(), Optional.empty(), 1));
		var nullProjectionTypes = new HashMap<EventType, Set<ProjectionType>>();
		nullProjectionTypes.put(POT_CREATED, null);
		assertThrows(NullPointerException.class, () -> discovery.findCandidates(
				nullProjectionTypes, WorkerSegment.single(), Optional.empty(), 1));
	}

	private List<ProjectionMaterializationOrderingKey> scan(
			Map<EventType, Set<ProjectionType>> routes, int limit) {
		var keys = new ArrayList<ProjectionMaterializationOrderingKey>();
		Optional<ProjectionMaterializationOrderingKey> cursor = Optional.empty();
		while (true) {
			var page = discovery.findCandidates(routes, WorkerSegment.single(), cursor, limit);
			if (page.isEmpty()) return List.copyOf(keys);
			for (var candidate : page) {
				keys.add(candidate.orderingKey());
				cursor = Optional.of(candidate.orderingKey());
			}
		}
	}

	private Claim acquire(UUID eventId, ProjectionType projectionType) {
		return ((Acquired) inTransaction(() -> lifecycle.acquire(
				key(eventId, projectionType), ClaimId.generate(), new WorkerId("test-worker"), LEASE, NOW))).claim();
	}

	private void done(UUID eventId, ProjectionType projectionType) {
		Claim claim = acquire(eventId, projectionType);
		assertTrue(inTransaction(() -> lifecycle.tryTerminalize(
				claim.slotId(), claim.claimId(), TerminalOutcome.SUCCESS, Optional.empty(), NOW.plusSeconds(1))));
	}

	private <T> T inTransaction(java.util.function.Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private static ConsumptionKey key(UUID eventId, ProjectionType projectionType) {
		return new ConsumptionKey(
				new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PROJECTION_TASK_MATERIALIZER", List.of(projectionType.value())));
	}

	private void insertEvent(UUID eventId, EventType eventType, UUID potId, long version,
			Instant createdAt, int partitionHash, String payload) {
		jdbc.update("""
				insert into business_event_outbox (
				  id, event_type, pot_id, pot_partition_hash, aggregate_id, version, payload_json,
				  status, attempt_count, created_at
				) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
				""", eventId, eventType.value(), potId, partitionHash, potId, version, payload,
				java.sql.Timestamp.from(createdAt));
	}

	private static Map<EventType, Set<ProjectionType>> routes(
			EventType eventType, ProjectionType projectionType) {
		return Map.of(eventType, Set.of(projectionType));
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = JpaConsumptionSlotEntity.class)
	@EnableJpaRepositories(basePackageClasses = JpaConsumptionSlotRepository.class)
	@Import({JdbcProjectionMaterializationDiscoveryAdapter.class, JpaConsumptionLifecycleAdapter.class})
	static class TestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
	}
}
