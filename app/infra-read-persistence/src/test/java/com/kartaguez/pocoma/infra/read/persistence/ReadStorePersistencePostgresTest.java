package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionPersistencePort;

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
	void readMigrationsInstallAutonomouslyAndRestartIdempotently() {
		contextRunner().run(context -> {
			assertTrue(context.getStartupFailure() == null,
					() -> "Autonomous read migration failed: " + context.getStartupFailure());
			assertEquals(0, context.getBeansOfType(Flyway.class).size());
			assertNotNull(context.getBean("readStoreMigrations"));
		});

		assertEquals(1, count("select count(*) from information_schema.schemata "
				+ "where schema_name='pocoma_read'"));
		assertEquals(0, count("select count(*) from information_schema.tables "
				+ "where table_schema='pocoma_read' and table_name='projection_coverages'"));

		contextRunner().run(context -> assertTrue(context.getStartupFailure() == null,
				() -> "Autonomous read migration restart failed: " + context.getStartupFailure()));
		assertEquals(1, count("select count(*) from pocoma_read.flyway_schema_history "
				+ "where success and version='1'"));
	}

	@Test
	void readStoreCompositionKeepsOnlyCanonicalAndLatestKnownVersionAccess() {
		contextRunner().run(context -> {
			assertTrue(context.getStartupFailure() == null,
					() -> "Read-only context failed: " + context.getStartupFailure());
			assertNotNull(context.getBean("readStoreJdbcOperations", JdbcOperations.class));
			assertNotNull(context.getBean(LatestKnownVersionPersistencePort.class));
			assertFalse(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType)
					.filter(java.util.Objects::nonNull).map(Class::getName)
					.anyMatch(name -> name.endsWith("JdbcProjectionMetadataAdapter")
							|| name.endsWith("JdbcPotUserIndexReader")
							|| name.endsWith("JdbcPotProjectionArtifactWriter")
							|| name.endsWith("SpringReadStoreTransactionRunner")));
		});
	}

	private ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						DataSourceAutoConfiguration.class,
						DataSourceTransactionManagerAutoConfiguration.class,
						ReadStoreAccessAutoConfiguration.class,
						ReadStoreMigrationAutoConfiguration.class))
				.withPropertyValues(
						"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"spring.datasource.username=" + POSTGRES.getUsername(),
						"spring.datasource.password=" + POSTGRES.getPassword(),
						"spring.datasource.driver-class-name=org.postgresql.Driver");
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}
}
