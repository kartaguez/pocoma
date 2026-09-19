package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionFailureId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionPublicationResult;

@Testcontainers
class JdbcProjectionStoreAdapterPostgresTest {
	private static final ArtifactType HEADER = new ArtifactType("HEADER");

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	private JdbcTemplate jdbc;
	private TransactionTemplate transactions;
	private JdbcProjectionStoreAdapter adapter;

	@BeforeEach
	void reset() {
		var dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("drop schema if exists pocoma_read cascade");
		new ReadStoreMigrator(dataSource, new ReadStoreProperties()).afterPropertiesSet();
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		adapter = new JdbcProjectionStoreAdapter(jdbc, transactions, new JsonValueCodec(), "pocoma_read");
	}

	@Test
	void publishesAndReadsAProjectionWithZeroArtifacts() {
		ProjectionKey key = key(1);

		assertEquals(ProjectionPublicationResult.PUBLISHED, adapter.publish(validated(key, List.of())));
		assertEquals(List.of(), adapter.findProjection(key).orElseThrow().artifacts());
	}

	@Test
	void migrationUsesInternalBigintIdentitiesAndMicrosecondFailureTimestamps() {
		assertEquals(List.of("bigint", "bigint"), jdbc.queryForList("""
				select data_type from information_schema.columns
				where table_schema = 'pocoma_read'
				  and table_name in ('projection_root', 'projection_artifact')
				  and column_name = 'id'
				order by table_name desc
				""", String.class));
		assertEquals(List.of("YES", "YES"), jdbc.queryForList("""
				select is_identity from information_schema.columns
				where table_schema = 'pocoma_read'
				  and table_name in ('projection_root', 'projection_artifact')
				  and column_name = 'id'
				order by table_name desc
				""", String.class));
		assertEquals(6, jdbc.queryForObject("""
				select datetime_precision from information_schema.columns
				where table_schema = 'pocoma_read' and table_name = 'projection_failure'
				  and column_name = 'failed_at'
				""", Integer.class));
	}

	@Test
	void readsArtifactsInCanonicalTechnicalOrderRatherThanInsertionOrder() {
		ProjectionArtifact b = artifact(HEADER, "b", "B");
		ProjectionArtifact a = artifact(HEADER, "a", "A");
		ProjectionKey firstKey = key(2);
		ProjectionKey secondKey = key(3);

		adapter.publish(validated(firstKey, List.of(b, a)));
		adapter.publish(validated(secondKey, List.of(a, b)));

		List<ProjectionArtifact> first = adapter.findProjection(firstKey).orElseThrow().artifacts();
		List<ProjectionArtifact> second = adapter.findProjection(secondKey).orElseThrow().artifacts();
		assertEquals(List.of(a, b), first);
		assertEquals(List.of(a, b), second);
		assertEquals(byIdentity(List.of(b, a)), byIdentity(first));
		assertEquals(byIdentity(List.of(a, b)), byIdentity(second));
	}

	@Test
	void firstPublishedProjectionWinsWithoutContentComparison() {
		ProjectionKey key = key(4);
		ProjectionArtifact first = artifact(HEADER, "same", "first");
		ProjectionArtifact other = artifact(HEADER, "same", "other");

		assertEquals(ProjectionPublicationResult.PUBLISHED,
				adapter.publish(validated(key, List.of(first))));
		assertEquals(ProjectionPublicationResult.ALREADY_EXISTS,
				adapter.publish(validated(key, List.of(other))));
		assertEquals(Map.of(new ArtifactIdentity(HEADER, new ArtifactKey("same")), first),
				byIdentity(adapter.findProjection(key).orElseThrow().artifacts()));
	}

	@Test
	void concurrentPublicationOfTheSameKeyPublishesExactlyOnce() throws Exception {
		ProjectionKey key = key(5);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				start.await();
				return adapter.publish(validated(key, List.of(artifact(HEADER, "same", "first"))));
			});
			var second = executor.submit(() -> {
				start.await();
				return adapter.publish(validated(key, List.of(artifact(HEADER, "same", "second"))));
			});
			start.countDown();
			assertEquals(Map.of(
					ProjectionPublicationResult.PUBLISHED, 1L,
					ProjectionPublicationResult.ALREADY_EXISTS, 1L),
					List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).stream()
							.collect(java.util.stream.Collectors.groupingBy(result -> result,
									java.util.stream.Collectors.counting())));
		}
		assertEquals(1, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(1, count("select count(*) from pocoma_read.projection_artifact"));
	}

	@Test
	void rollsBackRootAndEarlierArtifactsWhenAnArtifactInsertFails() {
		jdbc.execute("""
				create function pocoma_read.reject_failing_artifact() returns trigger language plpgsql as $$
				begin
				  if new.artifact_key = 'FAIL' then raise exception 'forced artifact failure'; end if;
				  return new;
				end
				$$
				""");
		jdbc.execute("""
				create trigger reject_failing_artifact before insert on pocoma_read.projection_artifact
				for each row execute function pocoma_read.reject_failing_artifact()
				""");

		assertThrows(DataAccessException.class, () -> adapter.publish(validated(key(6), List.of(
				artifact(HEADER, "ok", "first"), artifact(HEADER, "FAIL", "second")))));
		assertEquals(0, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(0, count("select count(*) from pocoma_read.projection_artifact"));
	}

	@Test
	void joinsACompatibleOuterTransactionAndRollsBackWithIt() {
		ProjectionKey key = key(7);
		assertThrows(ExpectedRollback.class, () -> transactions.executeWithoutResult(status -> {
			assertEquals(ProjectionPublicationResult.PUBLISHED,
					adapter.publish(validated(key, List.of(artifact(HEADER, "a", "A")))));
			throw new ExpectedRollback();
		}));
		assertTrue(adapter.findProjection(key).isEmpty());
	}

	@Test
	void exactFailureReplayAtMicrosecondPrecisionIsIdempotent() {
		ProjectionFailure failure = failure(UUID.randomUUID(), key(8),
				Instant.parse("2026-09-19T12:00:00.123456Z"));
		adapter.recordFailure(failure);
		adapter.recordFailure(failure);
		assertEquals(1, count("select count(*) from pocoma_read.projection_failure"));
	}

	@Test
	void nanosecondFailureReplayUsesTheCanonicalPersistedMicrosecond() {
		ProjectionFailure failure = failure(UUID.randomUUID(), key(9),
				Instant.parse("2026-09-19T12:00:00.123456789Z"));
		adapter.recordFailure(failure);
		adapter.recordFailure(failure);
		assertEquals(Instant.parse("2026-09-19T12:00:00.123456Z"), jdbc.queryForObject(
				"select failed_at from pocoma_read.projection_failure where failure_id = ?",
				(rs, rowNum) -> rs.getTimestamp(1).toInstant(), failure.id().value()));
	}

	@Test
	void differentJavaInstantsNormalizingToTheSameMicrosecondAreTheSamePersistedContent() {
		UUID id = UUID.randomUUID();
		ProjectionKey key = key(10);
		adapter.recordFailure(failure(id, key, Instant.parse("2026-09-19T12:00:00.123456111Z")));
		adapter.recordFailure(failure(id, key, Instant.parse("2026-09-19T12:00:00.123456999Z")));
		assertEquals(1, count("select count(*) from pocoma_read.projection_failure"));
	}

	@Test
	void rejectsFailureIdReusedWithDifferentPersistedContent() {
		UUID id = UUID.randomUUID();
		ProjectionKey key = key(11);
		adapter.recordFailure(failure(id, key, Instant.parse("2026-09-19T12:00:00.123456789Z")));

		assertThrows(ProjectionFailureIdConflictException.class, () -> adapter.recordFailure(
				failure(id, key, Instant.parse("2026-09-19T12:00:00.123457001Z"))));
		assertThrows(ProjectionFailureIdConflictException.class, () -> adapter.recordFailure(
				failure(id, key(12), Instant.parse("2026-09-19T12:00:00.123456789Z"))));
	}

	@Test
	void concurrentExactFailureReplaySucceedsAndStoresOneOccurrence() throws Exception {
		ProjectionFailure failure = failure(UUID.randomUUID(), key(13),
				Instant.parse("2026-09-19T12:00:00.123456789Z"));
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { start.await(); adapter.recordFailure(failure); return true; });
			var second = executor.submit(() -> { start.await(); adapter.recordFailure(failure); return true; });
			start.countDown();
			assertTrue(first.get(10, TimeUnit.SECONDS));
			assertTrue(second.get(10, TimeUnit.SECONDS));
		}
		assertEquals(1, count("select count(*) from pocoma_read.projection_failure"));
	}

	@Test
	void historicalFailuresCoexistWithAProjectionAndDistinctIdsRemainAppendOnly() {
		ProjectionKey key = key(14);
		adapter.recordFailure(failure(UUID.randomUUID(), key, Instant.parse("2026-09-19T12:00:00Z")));
		adapter.recordFailure(failure(UUID.randomUUID(), key, Instant.parse("2026-09-19T12:01:00Z")));
		assertTrue(adapter.hasFailure(key));

		adapter.publish(validated(key, List.of(artifact(HEADER, "a", "A"))));

		assertTrue(adapter.findProjection(key).isPresent());
		assertTrue(adapter.hasFailure(key));
		assertEquals(2, count("select count(*) from pocoma_read.projection_failure"));
		assertFalse(adapter.hasFailure(key(15)));
	}

	private static ValidatedProjection validated(ProjectionKey key, List<ProjectionArtifact> artifacts) {
		var cardinalities = new HashMap<ArtifactType, Integer>();
		artifacts.forEach(artifact -> cardinalities.merge(artifact.artifactType(), 1, Integer::sum));
		List<ArtifactDefinition> definitions = cardinalities.entrySet().stream()
				.map(entry -> new ArtifactDefinition(entry.getKey(),
						new Cardinality(0, null), JsonNull.INSTANCE))
				.toList();
		return new ProjectionValidator((schema, payload) -> true).validate(
				new ProjectionDefinition(key.projectionType(), key.targetObjectType(), definitions),
				new Projection(key, artifacts));
	}

	private static ProjectionArtifact artifact(ArtifactType type, String key, String value) {
		return new ProjectionArtifact(type, new ArtifactKey(key), new JsonString(value));
	}

	private static ProjectionKey key(long version) {
		return new ProjectionKey(new ProjectionType("TEST"), new TargetObjectType("POT"),
				new TargetObjectId("pot-1"), version);
	}

	private static ProjectionFailure failure(UUID id, ProjectionKey key, Instant failedAt) {
		return new ProjectionFailure(new ProjectionFailureId(id), key, failedAt);
	}

	private static Map<ArtifactIdentity, ProjectionArtifact> byIdentity(List<ProjectionArtifact> artifacts) {
		return artifacts.stream().collect(java.util.stream.Collectors.toMap(
				artifact -> new ArtifactIdentity(artifact.artifactType(), artifact.artifactKey()),
				artifact -> artifact));
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private record ArtifactIdentity(ArtifactType type, ArtifactKey key) {
	}

	private static final class ExpectedRollback extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
