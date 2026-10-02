package com.kartaguez.pocoma.infra.persistence.jpa.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ExternalIdentityBindingLifecycleMigrationPostgresTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@BeforeEach
	void migrateThroughV19() {
		Flyway flyway = flyway("19", true);
		flyway.clean();
		assertEquals(19, flyway.migrate().migrationsExecuted);
	}

	@Test
	void v20BootstrapsOneLocalRevisionZeroStreamPerHistoricalIdentityWithoutFacts() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into users (user_id) values
					('10000000-0000-0000-0000-000000000001'),
					('10000000-0000-0000-0000-000000000002')
					""");
			statement.executeUpdate("""
					insert into external_identities (issuer, subject, user_id, binding_id) values
					('issuer-a', 'subject-a', '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001'),
					('issuer-b', 'subject-b', '10000000-0000-0000-0000-000000000002',
					 '20000000-0000-0000-0000-000000000002')
					""");
		}

		assertEquals(1, flyway("20", false).migrate().migrationsExecuted);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertEquals(2, scalar(statement, "select count(*) from external_identity_binding_streams"));
			assertEquals(2, scalar(statement, "select count(*) from external_identity_binding_streams "
					+ "where current_revision = 0"));
			assertEquals(0, scalar(statement, "select count(*) from external_identity_binding_facts"));
			assertEquals(1, scalar(statement, "select count(distinct current_revision) "
					+ "from external_identity_binding_streams"));
			assertAuthorityUnchanged(statement, "issuer-a", "subject-a",
					"10000000-0000-0000-0000-000000000001",
					"20000000-0000-0000-0000-000000000001");
			assertAuthorityUnchanged(statement, "issuer-b", "subject-b",
					"10000000-0000-0000-0000-000000000002",
					"20000000-0000-0000-0000-000000000002");

			assertEquals(0, statement.executeUpdate("""
					insert into external_identity_binding_streams (issuer, subject, current_revision)
					select issuer, subject, 0 from external_identities
					on conflict (issuer, subject) do nothing
					"""));
			assertEquals(2, scalar(statement, "select count(*) from external_identity_binding_streams"));
		}
	}

	@Test
	void v20EnforcesLocalStreamAndCompleteKnownFactShapes() throws Exception {
		assertEquals(1, flyway("20", false).migrate().migrationsExecuted);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("insert into users (user_id) values "
					+ "('10000000-0000-0000-0000-000000000001')");
			statement.executeUpdate("insert into external_identity_binding_streams "
					+ "(issuer,subject,current_revision) values ('issuer-a','subject-a',0),"
					+ "('issuer-b','subject-b',0)");

			assertEquals(2, scalar(statement, "select count(*) from external_identity_binding_streams "
					+ "where current_revision=0"));
			assertSqlRejected(statement, "insert into external_identity_binding_streams values "
					+ "('issuer-c','subject-c',-1)");
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000000",
					"issuer-a", "subject-a", 0, "ATTACHED", true, true));

			statement.executeUpdate(factSql("30000000-0000-0000-0000-000000000001",
					"issuer-a", "subject-a", 1, "ATTACHED", true, true));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000002",
					"issuer-a", "subject-a", 1, "DETACHED", false, true));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000003",
					"issuer-a", "subject-a", 2, "UNKNOWN", false, true));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000004",
					"issuer-a", "subject-a", 2, "ATTACHED", false, true));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000005",
					"issuer-a", "subject-a", 2, "ATTACHED", true, false));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000006",
					"issuer-a", "subject-a", 2, "DETACHED", false, false));
			assertSqlRejected(statement, factSql("30000000-0000-0000-0000-000000000007",
					"issuer-a", "subject-a", 2, "DETACHED", true, true));

			statement.executeUpdate(factSql("30000000-0000-0000-0000-000000000008",
					"issuer-b", "subject-b", 1, "DETACHED", false, true));
			assertEquals(2, scalar(statement, "select count(*) from external_identity_binding_facts"));
		}
	}

	private static String factSql(String eventId, String issuer, String subject, long revision,
			String type, boolean withUser, boolean withBinding) {
		String userId = withUser ? "'10000000-0000-0000-0000-000000000001'" : "null";
		String bindingId = withBinding ? "'20000000-0000-0000-0000-000000000001'" : "null";
		return "insert into external_identity_binding_facts "
				+ "(event_id,issuer,subject,binding_revision,fact_type,user_id,binding_id,recorded_at,partition_hash) "
				+ "values ('" + eventId + "','" + issuer + "','" + subject + "'," + revision + ",'" + type
				+ "'," + userId + "," + bindingId + ",now(),42)";
	}

	private static void assertSqlRejected(Statement statement, String sql) {
		assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
	}

	private static void assertAuthorityUnchanged(Statement statement, String issuer, String subject,
			String userId, String bindingId) throws Exception {
		try (ResultSet result = statement.executeQuery("select user_id,binding_id from external_identities "
				+ "where issuer='" + issuer + "' and subject='" + subject + "'")) {
			assertTrue(result.next());
			assertEquals(userId, result.getString("user_id"));
			assertEquals(bindingId, result.getString("binding_id"));
		}
	}

	private static int scalar(Statement statement, String sql) throws Exception {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private static Flyway flyway(String target, boolean cleanEnabled) {
		var configuration = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.cleanDisabled(!cleanEnabled);
		if (target != null) configuration.target(target);
		return configuration.load();
	}

	private static Connection connection() throws Exception {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
