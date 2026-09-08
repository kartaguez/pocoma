package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.read.projection.ObserveSourceVersionInput;
import com.kartaguez.pocoma.engine.read.projection.SourceVersionObservation;

@Testcontainers
class SourceVersionWatermarkPersistencePostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	private JdbcTemplate jdbc;
	private TransactionTemplate transactions;
	private JdbcSourceVersionWatermarkAdapter adapter;

	@BeforeEach
	void reset() {
		var dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("drop schema if exists pocoma_read cascade");
		new ReadStoreMigrator(dataSource, new ReadStoreProperties()).afterPropertiesSet();
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		adapter = new JdbcSourceVersionWatermarkAdapter(jdbc, "pocoma_read");
	}

	@Test
	void firstObservationMayStartAboveOneAndOnlyRealAdvancesChangeAdvancedAt() {
		PotId potId = PotId.of(UUID.randomUUID());
		Instant first = Instant.parse("2026-01-01T00:00:00Z");
		Instant later = Instant.parse("2026-01-02T00:00:00Z");

		var created = observe(potId, 42, first);
		var equal = observe(potId, 42, later);
		var lower = observe(potId, 20, later);

		assertInstanceOf(SourceVersionObservation.Advanced.class, created);
		assertInstanceOf(SourceVersionObservation.Unchanged.class, equal);
		assertInstanceOf(SourceVersionObservation.Unchanged.class, lower);
		assertEquals(42L, version(potId));
		assertEquals(first, advancedAt(potId));

		assertInstanceOf(SourceVersionObservation.Advanced.class, observe(potId, 45, later));
		assertEquals(45L, version(potId));
		assertEquals(later, advancedAt(potId));
	}

	@Test
	void concurrentOutOfOrderObservationsKeepTheMaximum() throws Exception {
		PotId potId = PotId.of(UUID.randomUUID());
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(3)) {
			var a = executor.submit(() -> { await(start); return observe(potId, 42, Instant.now()); });
			var b = executor.submit(() -> { await(start); return observe(potId, 45, Instant.now()); });
			var c = executor.submit(() -> { await(start); return observe(potId, 45, Instant.now()); });
			start.countDown();
			a.get(10, TimeUnit.SECONDS);
			b.get(10, TimeUnit.SECONDS);
			c.get(10, TimeUnit.SECONDS);
		}
		assertEquals(45L, version(potId));
	}

	private SourceVersionObservation observe(PotId potId, long version, Instant at) {
		return transactions.execute(status -> adapter.observe(new ObserveSourceVersionInput(potId, version, at)));
	}

	private long version(PotId potId) {
		return jdbc.queryForObject("select latest_version_seen from pocoma_read.source_version_watermarks where pot_id=?",
				Long.class, potId.value());
	}

	private Instant advancedAt(PotId potId) {
		return jdbc.queryForObject("select advanced_at from pocoma_read.source_version_watermarks where pot_id=?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), potId.value());
	}

	private static void await(CountDownLatch latch) {
		try { latch.await(); }
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}
}
