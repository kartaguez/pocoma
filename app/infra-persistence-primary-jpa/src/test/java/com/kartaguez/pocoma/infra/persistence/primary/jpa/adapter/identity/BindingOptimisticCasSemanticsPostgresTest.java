package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Characterizes READ COMMITTED after an UPDATE waits on the permanent Binding stream row. */
@Testcontainers
class BindingOptimisticCasSemanticsPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@BeforeEach
	void schema() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("drop table if exists external_identities");
			statement.execute("drop table if exists external_identity_binding_streams");
			statement.execute("create table external_identity_binding_streams (issuer text not null, subject text not null, current_revision bigint not null, primary key (issuer, subject))");
			statement.execute("create table external_identities (issuer text not null, subject text not null, binding_id text not null, primary key (issuer, subject))");
			statement.execute("insert into external_identity_binding_streams values ('issuer', 'subject', 0)");
		}
	}

	@Test
	void negativeFenceDoesNotRecheckActiveBindingAfterWaitingForConcurrentInsert() throws Exception {
		try (Connection winner = connect(); Connection waiter = connect()) {
			winner.setAutoCommit(false);
			waiter.setAutoCommit(false);
			int waiterPid = scalar(waiter, "select pg_backend_pid()");
			// Hold the stream row while changing only the active state. This isolates
			// subquery re-evaluation from the revision equality check.
			assertEquals(1, execute(winner, "update external_identity_binding_streams set current_revision=current_revision where issuer='issuer' and subject='subject'"));
			assertEquals(1, execute(winner, "insert into external_identities values ('issuer','subject','B1')"));
			CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> {
				try {
					return execute(waiter, "update external_identity_binding_streams s set current_revision=s.current_revision "
							+ "where s.issuer='issuer' and s.subject='subject' and s.current_revision=0 "
							+ "and not exists (select 1 from external_identities a where a.issuer=s.issuer and a.subject=s.subject)");
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			awaitBlocked(waiterPid);
			winner.commit();
			assertEquals(1, result.get(10, TimeUnit.SECONDS),
					"PostgreSQL 17 rechecks the target row but retains the old subquery snapshot");
			assertEquals(1, scalar(winner, "select count(*) from external_identities where issuer='issuer' and subject='subject'"));
			waiter.rollback();
		}
	}

	private static void awaitBlocked(int pid) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			try (Connection observer = connect(); Statement statement = observer.createStatement();
					ResultSet result = statement.executeQuery("select cardinality(pg_blocking_pids(" + pid + "))")) {
				result.next();
				if (result.getInt(1) > 0) return;
			}
			Thread.sleep(20);
		}
		fail("The second transaction never waited on the stream row");
	}

	private static int execute(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement()) { return statement.executeUpdate(sql); }
	}

	private static int scalar(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
