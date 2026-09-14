package com.kartaguez.pocoma.infra.pipeline.lifecycle.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateMutationPort;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPipelineLifecycleAdapterPostgresTest {
	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	private static final PipelineDefinition V1 = new PipelineDefinition(PipelineId.of("read-pot"), 1);
	private static final PipelineDefinition V2 = new PipelineDefinition(PipelineId.of("read-pot"), 2);
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");

	private JdbcTemplate jdbc;
	private JdbcPipelineLifecycleAdapter adapter;
	private TransactionTemplate transactions;

	@BeforeEach
	void setUp() {
		var dataSource = new PGSimpleDataSource();
		dataSource.setURL(POSTGRES.getJdbcUrl());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
				.schemas("public", "pocoma_control").cleanDisabled(false).load().clean();
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
				.schemas("public", "pocoma_control").load().migrate();
		jdbc = new JdbcTemplate(dataSource);
		jdbc.update("delete from pocoma_control.projection_serving_selections");
		jdbc.update("delete from pocoma_control.pipeline_version_activations");
		adapter = new JdbcPipelineLifecycleAdapter(jdbc);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Test
	void idempotentTransitionsPreserveAuditTimestamps() {
		Instant first = Instant.parse("2026-09-14T08:00:00Z");
		Instant retry = first.plusSeconds(60);
		Instant changed = retry.plusSeconds(60);

		adapter.activate(V1, first);
		adapter.activate(V1, retry);
		assertEquals(first, adapter.activatedAt(V1).orElseThrow());

		assertEquals(PipelineLifecycleStateMutationPort.ServingMutationResult.SELECTED,
				adapter.selectServingIfActive(new ServingSelection(READ_POT, V1), first));
		assertEquals(PipelineLifecycleStateMutationPort.ServingMutationResult.UNCHANGED,
				adapter.selectServingIfActive(new ServingSelection(READ_POT, V1), retry));
		assertEquals(first, adapter.selectedAt(READ_POT).orElseThrow());

		adapter.activate(V2, retry);
		adapter.selectServingIfActive(new ServingSelection(READ_POT, V2), changed);
		assertEquals(changed, adapter.selectedAt(READ_POT).orElseThrow());

		adapter.clearServing(READ_POT);
		adapter.deactivateIfNotServing(V2);
		adapter.activate(V2, changed);
		assertEquals(changed, adapter.activatedAt(V2).orElseThrow());
	}

	@Test
	void servingCannotBeDeactivatedAndMissingActivationCannotServe() {
		adapter.activate(V1, Instant.now());
		adapter.selectServingIfActive(new ServingSelection(READ_POT, V1), Instant.now());
		assertEquals(PipelineLifecycleStateMutationPort.DeactivationResult.SERVING,
				adapter.deactivateIfNotServing(V1));
		assertEquals(PipelineLifecycleStateMutationPort.ServingMutationResult.INACTIVE,
				adapter.selectServingIfActive(new ServingSelection(READ_POT, V2), Instant.now()));
	}

	@Test
	void claimCommitBeforeDeactivateLetsTheAcquisitionWin() throws Exception {
		adapter.activate(V1, Instant.now());
		jdbc.execute("create table acquired_claims (id integer primary key)");
		var locked = new CountDownLatch(1);
		var allowCommit = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var claim = executor.submit(() -> transactions.executeWithoutResult(status -> {
				assertTrue(adapter.lockIfActive(V1));
				jdbc.update("insert into acquired_claims (id) values (1)");
				locked.countDown();
				await(allowCommit);
			}));
			assertTrue(locked.await(5, TimeUnit.SECONDS));
			var deactivate = executor.submit(() -> adapter.deactivateIfNotServing(V1));
			Thread.sleep(Duration.ofMillis(100));
			assertFalse(deactivate.isDone());
			allowCommit.countDown();
			claim.get(5, TimeUnit.SECONDS);
			assertEquals(PipelineLifecycleStateMutationPort.DeactivationResult.DEACTIVATED,
					deactivate.get(5, TimeUnit.SECONDS));
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from acquired_claims", Integer.class));
	}

	@Test
	void deactivateCommitBeforeClaimPreventsAcquisition() {
		adapter.activate(V1, Instant.now());
		assertEquals(PipelineLifecycleStateMutationPort.DeactivationResult.DEACTIVATED,
				adapter.deactivateIfNotServing(V1));
		boolean acquired = transactions.execute(status -> adapter.lockIfActive(V1));
		assertFalse(acquired);
		adapter.activate(V1, Instant.now());
		assertEquals(Boolean.TRUE, transactions.execute(status -> adapter.lockIfActive(V1)));
	}

	@Test
	void concurrentServingSelectionsRemainUnique() throws Exception {
		adapter.activate(V1, Instant.now());
		adapter.activate(V2, Instant.now());
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = executor.submit(() -> {
				await(start);
				return transactions.execute(status -> adapter.selectServingIfActive(
						new ServingSelection(READ_POT, V1), Instant.parse("2026-09-14T08:00:00Z")));
			});
			var second = executor.submit(() -> {
				await(start);
				return transactions.execute(status -> adapter.selectServingIfActive(
						new ServingSelection(READ_POT, V2), Instant.parse("2026-09-14T08:01:00Z")));
			});
			start.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		}
		assertEquals(1, jdbc.queryForObject(
				"select count(*) from pocoma_control.projection_serving_selections where projection_type='READ_POT'",
				Integer.class));
		assertTrue(adapter.findServing(READ_POT).orElseThrow().servingPipeline().equals(V1)
				|| adapter.findServing(READ_POT).orElseThrow().servingPipeline().equals(V2));
	}

	@Test
	void servingSelectionCommitBeforeDeactivateKeepsServingActive() throws Exception {
		adapter.activate(V1, Instant.now());
		var selected = new CountDownLatch(1);
		var allowCommit = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var select = executor.submit(() -> transactions.executeWithoutResult(status -> {
				adapter.selectServingIfActive(new ServingSelection(READ_POT, V1), Instant.now());
				selected.countDown();
				await(allowCommit);
			}));
			assertTrue(selected.await(5, TimeUnit.SECONDS));
			var deactivate = executor.submit(() -> transactions.execute(
					status -> adapter.deactivateIfNotServing(V1)));
			Thread.sleep(Duration.ofMillis(100));
			assertFalse(deactivate.isDone());
			allowCommit.countDown();
			select.get(5, TimeUnit.SECONDS);
			assertEquals(PipelineLifecycleStateMutationPort.DeactivationResult.SERVING,
					deactivate.get(5, TimeUnit.SECONDS));
		}
		assertTrue(adapter.isActive(V1));
		assertEquals(V1, adapter.findServing(READ_POT).orElseThrow().servingPipeline());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
	}
}
