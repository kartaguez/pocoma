package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JdbcProjectionTaskStoreAdapterPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired JdbcProjectionTaskStoreAdapter store;

	@BeforeEach
	void clean() {
		jdbc.update("delete from projection_tasks");
	}

	@Test
	void repeatedRequestsConvergeOnOneDurableTaskIdentifiedByProjectionKey() {
		ProjectionKey key = key(42);

		assertEquals(key, store.ensure(key, Instant.parse("2026-09-20T10:00:00Z")).projectionKey());
		assertEquals(key, store.ensure(key, Instant.parse("2026-09-20T10:01:00Z")).projectionKey());

		assertEquals(1, jdbc.queryForObject("select count(*) from projection_tasks", Integer.class));
		assertEquals(key, store.findCandidates(java.util.Set.of(key.projectionType()), 0, 1,
				Optional.empty(), Optional.empty(), 10).getFirst().task().projectionKey());
	}

	@Test
	void concurrentCreationUsesTheDatabaseUniquenessConstraintAsAuthority() throws Exception {
		ProjectionKey key = key(43);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { await(start); return store.ensure(key, Instant.now()); });
			var second = executor.submit(() -> { await(start); return store.ensure(key, Instant.now()); });
			start.countDown();
			assertEquals(key, first.get(10, TimeUnit.SECONDS).projectionKey());
			assertEquals(key, second.get(10, TimeUnit.SECONDS).projectionKey());
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from projection_tasks", Integer.class));
	}

	private static ProjectionKey key(long version) {
		return new ProjectionKey(new ProjectionType("READ_POT"), new TargetObjectType("POT"),
				new TargetObjectId(UUID.fromString("10000000-0000-0000-0000-000000000001").toString()), version);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(JdbcProjectionTaskStoreAdapter.class)
	static class TestApplication {}
}
