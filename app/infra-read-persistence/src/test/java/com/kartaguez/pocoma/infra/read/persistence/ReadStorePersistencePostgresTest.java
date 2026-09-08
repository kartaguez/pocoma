package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactDescriptor;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;
import com.kartaguez.pocoma.domain.projection.ProjectionContentDigest;
import com.kartaguez.pocoma.domain.projection.ProjectionCoverage;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.read.projection.ProjectionArtifactWriter;
import com.kartaguez.pocoma.engine.read.projection.ProjectionFailureResult;
import com.kartaguez.pocoma.engine.read.projection.ProjectionFailureService;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationResult;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationService;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMetadataPort;
import com.kartaguez.pocoma.engine.read.projection.ProjectionResolution;
import com.kartaguez.pocoma.engine.read.projection.ProjectionStatusResolver;
import com.kartaguez.pocoma.engine.read.projection.ReadStoreTransactionRunner;

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
	void readMigrationsInstallAutonomouslyWithoutPrimaryFlywayAndRestartIdempotently() {
		autonomousMigrationContextRunner().run(context -> {
			assertTrue(context.getStartupFailure() == null,
					() -> "Autonomous read migration failed: " + context.getStartupFailure());
			assertEquals(0, context.getBeansOfType(Flyway.class).size());
			assertNotNull(context.getBean("readStoreMigrations"));
		});

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

		autonomousMigrationContextRunner().run(context -> assertTrue(context.getStartupFailure() == null,
				() -> "Autonomous read migration restart failed: " + context.getStartupFailure()));

		assertEquals(1, count("""
				select count(*) from pocoma_read.flyway_schema_history where success and version = '1'
				"""));
	}

	@Test
	void readAccessRemainsAvailableWhenFlywayIsDisabled() {
		accessContextRunner()
				.withPropertyValues("spring.flyway.enabled=false")
				.run(context -> {
					assertTrue(context.getStartupFailure() == null,
							() -> "Context failed to start: " + context.getStartupFailure());
					assertNotNull(context.getBean("readStoreJdbcOperations", JdbcOperations.class));
					assertNotNull(context.getBean(
							"readStoreTransactionOperations", TransactionOperations.class));
					assertEquals(0, context.getBeansOfType(Flyway.class).size());
					assertTrue(!context.containsBean("readStoreMigrations"));
					assertEquals(0, count("""
							select count(*) from information_schema.schemata where schema_name = 'pocoma_read'
							"""));
				});
	}

	@Test
	void springCompositionKeepsPrimaryFlywayAndUsesOneDatasourceAndTransactionManager() {
		primaryAndReadContextRunner().run(context -> {
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

		primaryAndReadContextRunner().run(context -> {
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
		primaryAndReadContextRunner().run(context -> {
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

	@Test
	void coverageIsContinuousAndConcurrentExtensionsAreMonotone() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ProjectionGenerationIdentity generation = generation();
			assertEquals(new ProjectionCoverage(generation, 10, 20),
					metadata.createCoverage(new ProjectionCoverage(generation, 10, 20)));

			var ready = new CountDownLatch(2);
			var start = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var through101 = executor.submit(() -> {
					ready.countDown();
					start.await();
					return metadata.extendCoverageThrough(generation, 101);
				});
				var through102 = executor.submit(() -> {
					ready.countDown();
					start.await();
					return metadata.extendCoverageThrough(generation, 102);
				});
				assertTrue(ready.await(5, TimeUnit.SECONDS));
				start.countDown();
				through101.get(5, TimeUnit.SECONDS);
				through102.get(5, TimeUnit.SECONDS);
			}

			metadata.extendCoverageFrom(generation, 5);
			assertEquals(new ProjectionCoverage(generation, 5, 102), metadata.findCoverage(generation).orElseThrow());
		});
	}

	@Test
	void statusIsDerivedFromCoverageArtifactAndFailure() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ProjectionStatusResolver resolver = context.getBean(ProjectionStatusResolver.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			ProjectionGenerationIdentity generation = generation();
			metadata.createCoverage(new ProjectionCoverage(generation, 10, 20));

			assertInstanceOf(ProjectionResolution.NotExpected.class, resolver.resolve(identity(generation, 9)));
			assertEquals(ProjectionStatus.NOT_READY,
					((ProjectionResolution.Resolved) resolver.resolve(identity(generation, 15))).status());

			var failureService = new ProjectionFailureService(metadata, transactions);
			assertInstanceOf(ProjectionFailureResult.Recorded.class,
					failureService.record(identity(generation, 15), Instant.parse("2026-01-01T00:00:00Z"), "TERMINAL"));
			assertEquals(ProjectionStatus.FAILED,
					((ProjectionResolution.Resolved) resolver.resolve(identity(generation, 15))).status());
		});
	}

	@Test
	void materializationIsImmutableIdempotentAtomicAndAdvancesHeadOutOfOrder() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			metadata.createCoverage(new ProjectionCoverage(generation, 1, 100));
			var service = materializationService(metadata, transactions, readJdbc);

			assertInstanceOf(ProjectionMaterializationResult.Created.class,
					service.materialize(identity(generation, 44), "forty-four"));
			assertInstanceOf(ProjectionMaterializationResult.Created.class,
					service.materialize(identity(generation, 46), "forty-six"));
			assertInstanceOf(ProjectionMaterializationResult.Created.class,
					service.materialize(identity(generation, 45), "forty-five"));
			assertEquals(46, metadata.findHead(generation).orElseThrow().latestProjectedVersion());

			assertInstanceOf(ProjectionMaterializationResult.AlreadySatisfied.class,
					service.materialize(identity(generation, 44), "forty-four"));
			assertInstanceOf(ProjectionMaterializationResult.DivergentDuplicate.class,
					service.materialize(identity(generation, 44), "different"));
			assertEquals("forty-four", readJdbc.queryForObject(
					"select content from pocoma_read.test_projection_artifacts where pot_version=44", String.class));
			assertEquals(1, readJdbc.queryForObject(
					"select count(*) from pocoma_read.projection_invariant_violations", Integer.class));
			assertEquals(ProjectionStatus.READY,
					((ProjectionResolution.Resolved) context.getBean(ProjectionStatusResolver.class)
							.resolve(identity(generation, 44))).status());
		});
	}

	@Test
	void firstTerminalOutcomeWinsBetweenSuccessAndFailure() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			metadata.createCoverage(new ProjectionCoverage(generation, 1, 100));
			var materialization = materializationService(metadata, transactions, readJdbc);
			var failures = new ProjectionFailureService(metadata, transactions);

			materialization.materialize(identity(generation, 50), "success-first");
			assertInstanceOf(ProjectionFailureResult.AlreadyReady.class,
					failures.record(identity(generation, 50), Instant.now(), "LATE_FAILURE"));

			failures.record(identity(generation, 51), Instant.now(), "FAILURE_FIRST");
			assertInstanceOf(ProjectionMaterializationResult.AlreadyFailed.class,
					materialization.materialize(identity(generation, 51), "late-success"));
			assertEquals(0, readJdbc.queryForObject("""
					select count(*) from pocoma_read.projection_artifacts artifact
					join pocoma_read.projection_failures failure
					  using (projection_type,pipeline_id,pipeline_version,pot_id,pot_version)
					""", Integer.class));
		});
	}

	@Test
	void failedConcreteArtifactWriteRollsBackDescriptorAndHead() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			ProjectionIdentity identity = identity(generation, 70);
			metadata.createCoverage(new ProjectionCoverage(generation, 1, 100));
			var failingWriter = new ProjectionArtifactWriter<String>() {
				@Override public ProjectionContentDigest digest(String artifact) { return digestOf(artifact); }
				@Override public void write(ProjectionArtifactId artifactId, ProjectionIdentity ignored, String artifact) {
					readJdbc.update("insert into pocoma_read.test_projection_artifacts(artifact_id,pot_version,content) values (?,?,?)",
							artifactId.value(), identity.potVersion(), artifact);
					throw new ExpectedRollback();
				}
				@Override public boolean hasSameContent(ProjectionArtifactDescriptor existing, String artifact) { return false; }
			};

			assertThrows(ExpectedRollback.class, () -> new ProjectionMaterializationService<>(metadata, transactions,
					failingWriter, Clock.systemUTC()).materialize(identity, "rollback"));
			assertEquals(0, readJdbc.queryForObject(
					"select count(*) from pocoma_read.test_projection_artifacts where pot_version=70", Integer.class));
			assertTrue(metadata.findArtifact(identity).isEmpty());
			assertTrue(metadata.findHead(generation).isEmpty());
		});
	}

	@Test
	void concurrentIdenticalMaterializationsCreateOneArtifact() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			ProjectionIdentity identity = identity(generation, 60);
			metadata.createCoverage(new ProjectionCoverage(generation, 1, 100));
			var service = materializationService(metadata, transactions, readJdbc);
			var start = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var first = executor.submit(() -> { start.await(); return service.materialize(identity, "same"); });
				var second = executor.submit(() -> { start.await(); return service.materialize(identity, "same"); });
				start.countDown();
				var firstResult = first.get(10, TimeUnit.SECONDS);
				var secondResult = second.get(10, TimeUnit.SECONDS);
				assertTrue(firstResult instanceof ProjectionMaterializationResult.Created
						|| secondResult instanceof ProjectionMaterializationResult.Created);
				assertTrue(firstResult instanceof ProjectionMaterializationResult.AlreadySatisfied
						|| secondResult instanceof ProjectionMaterializationResult.AlreadySatisfied);
			}
			assertEquals(1, readJdbc.queryForObject(
					"select count(*) from pocoma_read.projection_artifacts where pot_version=60", Integer.class));
		});
	}

	@Test
	void concurrentSuccessAndFailureCommitExactlyOneTerminalOutcome() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			ProjectionIdentity identity = identity(generation, 80);
			metadata.createCoverage(new ProjectionCoverage(generation, 1, 100));
			var materialization = materializationService(metadata, transactions, readJdbc);
			var failures = new ProjectionFailureService(metadata, transactions);
			var start = new CountDownLatch(1);

			try (var executor = Executors.newFixedThreadPool(2)) {
				var success = executor.submit(() -> { start.await(); return materialization.materialize(identity, "success"); });
				var failure = executor.submit(() -> { start.await(); return failures.record(
						identity, Instant.parse("2026-01-01T00:00:00Z"), "TERMINAL"); });
				start.countDown();
				Object successResult = success.get(10, TimeUnit.SECONDS);
				Object failureResult = failure.get(10, TimeUnit.SECONDS);
				assertTrue(successResult instanceof ProjectionMaterializationResult.Created
						&& failureResult instanceof ProjectionFailureResult.AlreadyReady
						|| successResult instanceof ProjectionMaterializationResult.AlreadyFailed
						&& failureResult instanceof ProjectionFailureResult.Recorded);
			}

			assertEquals(1, readJdbc.queryForObject("""
					select
					  (select count(*) from pocoma_read.projection_artifacts where pot_version=80)
					+ (select count(*) from pocoma_read.projection_failures where pot_version=80)
					""", Integer.class));
		});
	}

	private ApplicationContextRunner autonomousMigrationContextRunner() {
		return new ApplicationContextRunner()
				.withBean(DataSource.class, () -> dataSource)
				.withConfiguration(AutoConfigurations.of(ReadStoreMigrationAutoConfiguration.class));
	}

	private ApplicationContextRunner accessContextRunner() {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						DataSourceAutoConfiguration.class,
						DataSourceTransactionManagerAutoConfiguration.class,
						FlywayAutoConfiguration.class,
						ReadStoreAccessAutoConfiguration.class,
						ReadStoreMigrationAutoConfiguration.class))
				.withPropertyValues(
						"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"spring.datasource.username=" + POSTGRES.getUsername(),
						"spring.datasource.password=" + POSTGRES.getPassword(),
						"spring.datasource.driver-class-name=org.postgresql.Driver");
	}

	private ApplicationContextRunner primaryAndReadContextRunner() {
		return accessContextRunner()
				.withPropertyValues(
						"spring.flyway.enabled=true",
						"spring.flyway.locations=classpath:db/primary-test-migration");
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private static ProjectionGenerationIdentity generation() {
		return new ProjectionGenerationIdentity(new ProjectionType("TEST"),
				new PipelineDefinition(PipelineId.of("READ_TEST"), 2), PotId.of(UUID.randomUUID()));
	}

	private static ProjectionIdentity identity(ProjectionGenerationIdentity generation, long version) {
		return new ProjectionIdentity(generation, version);
	}

	private static void createTestArtifactTable(JdbcOperations jdbc) {
		jdbc.execute("""
				create table pocoma_read.test_projection_artifacts (
				    artifact_id uuid primary key,
				    pot_version bigint not null,
				    content varchar(255) not null
				)
				""");
	}

	private static ProjectionMaterializationService<String> materializationService(ProjectionMetadataPort metadata,
			ReadStoreTransactionRunner transactions, JdbcOperations jdbc) {
		return new ProjectionMaterializationService<>(metadata, transactions, new ProjectionArtifactWriter<>() {
			@Override
			public ProjectionContentDigest digest(String artifact) {
				return digestOf(artifact);
			}

			@Override
			public void write(ProjectionArtifactId artifactId, ProjectionIdentity identity, String artifact) {
				jdbc.update("insert into pocoma_read.test_projection_artifacts(artifact_id,pot_version,content) values (?,?,?)",
						artifactId.value(), identity.potVersion(), artifact);
			}

			@Override
			public boolean hasSameContent(ProjectionArtifactDescriptor existing, String proposedArtifact) {
				return proposedArtifact.equals(jdbc.queryForObject(
						"select content from pocoma_read.test_projection_artifacts where artifact_id=?",
						String.class, existing.artifactId().value()));
			}
		}, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
	}

	private static ProjectionContentDigest digestOf(String artifact) {
		try {
			return new ProjectionContentDigest(HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(artifact.getBytes(StandardCharsets.UTF_8))));
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class ExpectedRollback extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
