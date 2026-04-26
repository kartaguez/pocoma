package com.kartaguez.pocoma.infra.tx.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class SpringTransactionRunnerTest {

	private final EmbeddedDatabase dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.build();
	private final SpringTransactionRunner transactionRunner = new SpringTransactionRunner(transactionTemplate(dataSource));

	@AfterEach
	void shutDownDatabase() {
		dataSource.shutdown();
	}

	@Test
	void runsActionInTransaction() {
		AtomicBoolean transactionActive = new AtomicBoolean(false);

		transactionRunner.runInTransaction(() ->
				transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive()));

		assertTrue(transactionActive.get());
	}

	@Test
	void runsAfterCommitActionAfterTransactionCommits() {
		AtomicBoolean published = new AtomicBoolean(false);

		transactionRunner.runInTransaction(() -> {
			transactionRunner.runAfterCommit(() -> published.set(true));
			assertFalse(published.get());
		});

		assertTrue(published.get());
	}

	@Test
	void doesNotRunAfterCommitActionWhenTransactionRollsBack() {
		AtomicBoolean published = new AtomicBoolean(false);

		assertThrows(RuntimeException.class, () -> transactionRunner.runInTransaction(() -> {
			transactionRunner.runAfterCommit(() -> published.set(true));
			throw new RuntimeException("rollback");
		}));

		assertFalse(published.get());
	}

	@Test
	void runsAfterCommitActionImmediatelyWhenNoTransactionIsActive() {
		AtomicBoolean published = new AtomicBoolean(false);

		transactionRunner.runAfterCommit(() -> published.set(true));

		assertTrue(published.get());
	}
	private static TransactionTemplate transactionTemplate(DataSource dataSource) {
		return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}
}
