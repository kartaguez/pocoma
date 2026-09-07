package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ReadStorePersistencePostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma")
			.withUsername("pocoma")
			.withPassword("pocoma");

	private DataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeEach
	void resetSchemas() {
		dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("drop schema if exists pocoma_read cascade");
		jdbc.execute("drop schema if exists public cascade");
		jdbc.execute("create schema public");
	}

	@Test
	void readMigrationsInstallAutonomouslyAndRestartIdempotently() throws Exception {
		ReadStoreProperties properties = new ReadStoreProperties();
		new ReadStoreMigrator(dataSource, properties).afterPropertiesSet();

		assertEquals(1, count("""
				select count(*) from information_schema.schemata where schema_name = 'pocoma_read'
				"""));
		assertEquals(1, count("""
				select count(*) from information_schema.tables
				where table_schema = 'pocoma_read' and table_name = 'flyway_schema_history'
				"""));
		assertEquals(0, count("""
				select count(*) from information_schema.tables where table_schema = 'public'
				"""));

		new ReadStoreMigrator(dataSource, properties).afterPropertiesSet();

		assertEquals(1, count("""
				select count(*) from pocoma_read.flyway_schema_history where success and version = '1'
				"""));
	}

	@Test
	void springCompositionKeepsPrimaryFlywayAndUsesOneDatasourceAndTransactionManager() {
		contextRunner().run(context -> {
			assertTrue(context.getStartupFailure() == null,
					() -> "Context failed to start: " + context.getStartupFailure());
			assertEquals(1, context.getBeansOfType(Flyway.class).size());
			assertEquals(1, context.getBeansOfType(DataSource.class).size());
			assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
			assertNotNull(context.getBean("readStoreMigrations"));
			assertEquals(1, count("""
					select count(*) from information_schema.tables
					where table_schema = 'public' and table_name = 'primary_test_marker'
					"""));
			assertEquals(1, count("""
					select count(*) from information_schema.tables
					where table_schema = 'pocoma_read' and table_name = 'flyway_schema_history'
					"""));
		});

		contextRunner().run(context -> {
			assertTrue(context.getStartupFailure() == null,
					() -> "Restarted context failed: " + context.getStartupFailure());
			assertEquals(1, count("""
					select count(*) from public.flyway_schema_history where success and version = '1'
					"""));
			assertEquals(1, count("""
					select count(*) from pocoma_read.flyway_schema_history where success and version = '1'
					"""));
		});
	}

	@Test
	void qualifiedReadTransactionRollsBackWithoutCreatingPhysicalIsolation() {
		contextRunner().run(context -> {
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			TransactionOperations readTransactions = context.getBean(
					"readStoreTransactionOperations", TransactionOperations.class);
			DataSource configuredDataSource = context.getBean(DataSource.class);

			readJdbc.execute("""
					create table pocoma_read.read_store_transaction_probe (
					    value integer not null
					)
					""");

			assertThrows(ExpectedRollback.class, () -> readTransactions.executeWithoutResult(status -> {
				readJdbc.update("insert into pocoma_read.read_store_transaction_probe(value) values (1)");
				readJdbc.update("insert into pocoma_read.read_store_transaction_probe(value) values (2)");
				throw new ExpectedRollback();
			}));

			assertEquals(0, readJdbc.queryForObject(
					"select count(*) from pocoma_read.read_store_transaction_probe", Integer.class));
			assertEquals(configuredDataSource, ((JdbcTemplate) readJdbc).getDataSource());
		});
	}

	@Test
	void readSchemaHasNoStructuralDependencyOnPrimaryApplicationObjects() throws Exception {
		new ReadStoreMigrator(dataSource, new ReadStoreProperties()).afterPropertiesSet();

		assertEquals(0, count("""
				select count(*)
				from pg_constraint constraint_definition
				join pg_class read_relation on read_relation.oid = constraint_definition.conrelid
				join pg_namespace read_namespace on read_namespace.oid = read_relation.relnamespace
				join pg_class primary_relation on primary_relation.oid = constraint_definition.confrelid
				join pg_namespace primary_namespace on primary_namespace.oid = primary_relation.relnamespace
				where constraint_definition.contype = 'f'
				  and read_namespace.nspname = 'pocoma_read'
				  and primary_namespace.nspname = 'public'
				"""));
		assertEquals(0, count("""
				select count(distinct read_view.oid)
				from pg_class read_view
				join pg_namespace read_namespace on read_namespace.oid = read_view.relnamespace
				join pg_rewrite rewrite_rule on rewrite_rule.ev_class = read_view.oid
				join pg_depend dependency on dependency.objid = rewrite_rule.oid
				join pg_class primary_relation on primary_relation.oid = dependency.refobjid
				join pg_namespace primary_namespace on primary_namespace.oid = primary_relation.relnamespace
				where read_namespace.nspname = 'pocoma_read'
				  and read_view.relkind in ('v', 'm')
				  and primary_namespace.nspname = 'public'
				  and primary_relation.relkind in ('r', 'p', 'v', 'm', 'S')
				"""));
	}

	private ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						DataSourceAutoConfiguration.class,
						DataSourceTransactionManagerAutoConfiguration.class,
						FlywayAutoConfiguration.class,
						ReadStorePersistenceAutoConfiguration.class))
				.withPropertyValues(
						"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"spring.datasource.username=" + POSTGRES.getUsername(),
						"spring.datasource.password=" + POSTGRES.getPassword(),
						"spring.datasource.driver-class-name=org.postgresql.Driver",
						"spring.flyway.enabled=true",
						"spring.flyway.locations=classpath:db/primary-test-migration");
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private static final class ExpectedRollback extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
