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
import java.util.List;
import java.util.Optional;
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
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.pipeline.UnknownPipelineDefinitionException;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactDescriptor;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;
import com.kartaguez.pocoma.domain.projection.ProjectionContentDigest;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpense;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpenseShare;
import com.kartaguez.pocoma.domain.projection.PotProjectionShareholder;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.projection.PotVersionMetadata;
import com.kartaguez.pocoma.engine.read.projection.ProjectionArtifactWriter;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexQuery;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexReader;
import com.kartaguez.pocoma.engine.read.projection.ProjectionFailureResult;
import com.kartaguez.pocoma.engine.read.projection.ProjectionFailureService;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationResult;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMaterializationService;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMetadataPort;
import com.kartaguez.pocoma.engine.read.projection.ProjectionResolution;
import com.kartaguez.pocoma.engine.read.projection.ProjectionStatusResolver;
import com.kartaguez.pocoma.engine.read.projection.ReadStoreTransactionRunner;
import com.kartaguez.pocoma.engine.read.projection.ReconstructedPotProjection;
import com.kartaguez.pocoma.engine.read.projection.SelectedPotPipelineRange;

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
		assertEquals(0, count("""
				select count(*) from information_schema.tables
				where table_schema = 'pocoma_read' and table_name = 'projection_coverages'
				"""));
		assertEquals(7, count("""
				select count(*) from information_schema.table_constraints
				where constraint_schema = 'pocoma_read' and constraint_type = 'FOREIGN KEY'
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
	void statusIsDerivedFromApplicabilityArtifactAndFailure() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ProjectionStatusResolver resolver = new ProjectionStatusResolver(metadata, registry(10, 20));
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			ProjectionGenerationIdentity generation = generation();
			assertInstanceOf(ProjectionResolution.NotApplicable.class, resolver.resolve(identity(generation, 9)));
			var unknown = new ProjectionGenerationIdentity(generation.projectionType(),
					new PipelineDefinition(PipelineId.of("UNKNOWN"), 1), generation.potId());
			assertThrows(UnknownPipelineDefinitionException.class,
					() -> resolver.resolve(identity(unknown, 15)));
			assertEquals(ProjectionStatus.NOT_READY,
					((ProjectionResolution.Resolved) resolver.resolve(identity(generation, 15))).status());

			var failureService = new ProjectionFailureService(metadata, transactions, registry(10, 20));
			assertInstanceOf(ProjectionFailureResult.NotApplicable.class,
					failureService.record(identity(generation, 9), Instant.now(), "IGNORED"));
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
			var service = materializationService(metadata, transactions, readJdbc);
			assertInstanceOf(ProjectionMaterializationResult.NotApplicable.class,
					service.materialize(identity(generation, 101), "outside"));

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
					((ProjectionResolution.Resolved) new ProjectionStatusResolver(metadata, registry(1, 100))
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
			var materialization = materializationService(metadata, transactions, readJdbc);
			var failures = new ProjectionFailureService(metadata, transactions, registry(1, 100));

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
					failingWriter, Clock.systemUTC(), registry(1, 100)).materialize(identity, "rollback"));
			assertEquals(0, readJdbc.queryForObject(
					"select count(*) from pocoma_read.test_projection_artifacts where pot_version=70", Integer.class));
			assertTrue(metadata.findArtifact(identity).isEmpty());
			assertTrue(metadata.findHead(generation).isEmpty());
		});
	}

	@Test
	void canonicalPotArtifactIsAutonomousExactlyLoadableAndDeterministic() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcPotProjectionArtifactWriter writer = context.getBean(JdbcPotProjectionArtifactWriter.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			PotId potId = PotId.of(UUID.randomUUID());
			ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
			ProjectionIdentity identity = new ProjectionIdentity(new ProjectionGenerationIdentity(
					new ProjectionType("READ_POT"), new PipelineDefinition(PipelineId.of("read-pot"), 1), potId), 5);
			PotProjection projection = new PotProjection(identity, PotProjectionStatus.DELETED, "Trip",
					UserId.of(UUID.randomUUID()),
					List.of(new PotProjectionShareholder(shareholderId, "Alice", Fraction.of(2, 4),
							Optional.empty(), true)),
					List.of(new PotProjectionExpense(ExpenseId.of(UUID.randomUUID()), shareholderId,
							Fraction.of(15, 2), "Dinner", true,
							List.of(new PotProjectionExpenseShare(shareholderId, Fraction.of(3, 6))))));
			var definitions = new PipelineDefinitionRegistry(List.of(new PipelineVersionDefinition(
					identity.generation().pipeline(), VersionApplicability.from(1))));
			var service = new ProjectionMaterializationService<>(metadata, transactions, writer,
					Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), definitions);
			var reconstructed = new ReconstructedPotProjection(
					projection,
					new PotVersionMetadata(potId, 5, Instant.parse("2025-12-31T23:59:59Z")));

			var created = assertInstanceOf(ProjectionMaterializationResult.Created.class,
					service.materialize(identity, reconstructed));
			assertEquals(projection, writer.findByArtifactId(created.descriptor().artifactId()).orElseThrow());
			assertEquals(writer.digestProjection(projection), writer.digestProjection(writer.findByArtifactId(
					created.descriptor().artifactId()).orElseThrow()));
			assertInstanceOf(ProjectionMaterializationResult.AlreadySatisfied.class,
					service.materialize(identity, reconstructed));
			var divergentMetadata = new ReconstructedPotProjection(
					projection,
					new PotVersionMetadata(potId, 5, Instant.parse("2026-01-01T00:00:01Z")));
			assertInstanceOf(ProjectionMaterializationResult.DivergentDuplicate.class,
					service.materialize(identity, divergentMetadata));
			assertEquals(1, readJdbc.queryForObject(
					"select count(*) from pocoma_read.pot_version_metadata where pot_id=? and pot_version=5",
					Integer.class, potId.value()));
			assertEquals(1, readJdbc.queryForObject(
					"select count(*) from pocoma_read.pot_projection_user_index "
							+ "where artifact_id=? and user_id=? and pot_status='DELETED'",
					Integer.class, created.descriptor().artifactId().value(), projection.creatorId().value()));
			assertEquals(Instant.parse("2025-12-31T23:59:59Z"), readJdbc.queryForObject(
					"select created_at from pocoma_read.pot_version_metadata where pot_id=? and pot_version=5",
					(rs, row) -> rs.getTimestamp(1).toInstant(), potId.value()));
			assertThrows(RuntimeException.class, () -> readJdbc.update(
					"update pocoma_read.pot_version_metadata set created_at=? where pot_id=? and pot_version=5",
					java.sql.Timestamp.from(Instant.EPOCH), potId.value()));
		});
	}

	@Test
	void currentUserIndexUsesIndividualWatermarksGenerationRangesAndKeysetOrder() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcPotProjectionArtifactWriter writer = context.getBean(JdbcPotProjectionArtifactWriter.class);
			PotUserIndexReader reader = context.getBean(PotUserIndexReader.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			UserId userId = UserId.of(UUID.fromString("00000000-0000-0000-0000-000000000099"));
			Instant newest = Instant.parse("2026-09-10T06:00:00Z");
			Instant tied = Instant.parse("2026-09-10T05:00:00Z");
			PotId firstId = PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
			PotId secondId = PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
			PotId newestId = PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000003"));
			var definition = new PipelineDefinition(PipelineId.of("read-pot"), 1);
			var secondGenerationDefinition = new PipelineDefinition(PipelineId.of("read-pot"), 2);
			var definitions = new PipelineDefinitionRegistry(List.of(
					new PipelineVersionDefinition(definition, VersionApplicability.from(1)),
					new PipelineVersionDefinition(secondGenerationDefinition, VersionApplicability.from(1))));
			var service = new ProjectionMaterializationService<>(metadata, transactions, writer,
					Clock.systemUTC(), definitions);

			materializeMinimalPot(service, definition, firstId, 2, userId, newest.plusSeconds(1));
			materializeMinimalPot(service, definition, firstId, 1, userId, tied);
			materializeMinimalPot(service, definition, secondId, 1, userId, tied);
			materializeMinimalPot(service, definition, newestId, 1, userId, newest);
			materializeMinimalPot(service, secondGenerationDefinition, firstId, 1, userId, tied);
			for (PotId potId : List.of(firstId, secondId, newestId)) {
				readJdbc.update("insert into pocoma_read.source_version_watermarks "
						+ "(pot_id,latest_version_seen,advanced_at) values (?,1,?)",
						potId.value(), java.sql.Timestamp.from(newest));
			}

			var range = new SelectedPotPipelineRange(1, 1, java.util.OptionalLong.empty());
			var firstPage = reader.findProjectedPots(new PotUserIndexQuery(
					userId, definition.pipelineId(), List.of(range), false, 2, Optional.empty()));
			assertEquals(List.of(newestId, firstId), firstPage.entries().stream()
					.map(entry -> entry.potId()).toList());
			assertTrue(firstPage.entries().stream().allMatch(entry -> entry.pipeline().pipelineVersion() == 1));
			assertEquals(3, readJdbc.queryForObject(
					"select count(*) from pocoma_read.pot_projection_user_index where pot_id=? and user_id=?",
					Integer.class, firstId.value(), userId.value()));
			var firstGeneration = new ProjectionGenerationIdentity(
					new ProjectionType("READ_POT"), definition, firstId);
			assertEquals(2, metadata.findHead(firstGeneration).orElseThrow().latestProjectedVersion());
			assertTrue(firstPage.nextCursor().isPresent());

			var secondPage = reader.findProjectedPots(new PotUserIndexQuery(
					userId, definition.pipelineId(), List.of(range), false, 2, firstPage.nextCursor()));
			assertEquals(List.of(secondId), secondPage.entries().stream()
					.map(entry -> entry.potId()).toList());
			assertTrue(secondPage.nextCursor().isEmpty());
		});
	}

	@Test
	void failureDuringUserIndexWriteRollsBackMetadataFragmentsArtifactAndHead() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcPotProjectionArtifactWriter writer = context.getBean(JdbcPotProjectionArtifactWriter.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			PotId potId = PotId.of(UUID.randomUUID());
			UserId creator = UserId.of(UUID.fromString("00000000-0000-0000-0000-000000000010"));
			UserId rejectedUser = UserId.of(UUID.fromString("00000000-0000-0000-0000-000000000020"));
			ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
			var definition = new PipelineDefinition(PipelineId.of("read-pot"), 1);
			var generation = new ProjectionGenerationIdentity(new ProjectionType("READ_POT"), definition, potId);
			var identity = new ProjectionIdentity(generation, 1);
			var projection = new PotProjection(identity, PotProjectionStatus.ACTIVE, "Rollback", creator,
					List.of(new PotProjectionShareholder(shareholderId, "Rejected", Fraction.ONE,
							Optional.of(rejectedUser), false)), List.of());
			var reconstructed = new ReconstructedPotProjection(projection,
					new PotVersionMetadata(potId, 1, Instant.parse("2026-09-10T05:00:00Z")));
			readJdbc.execute("""
					create function pocoma_read.reject_selected_user() returns trigger language plpgsql as $$
					begin
					  if new.user_id = '00000000-0000-0000-0000-000000000020'::uuid then
					    raise exception 'injected user index failure';
					  end if;
					  return new;
					end $$
					""");
			readJdbc.execute("""
					create trigger reject_selected_user before insert on pocoma_read.pot_projection_user_index
					for each row execute function pocoma_read.reject_selected_user()
					""");
			var definitions = new PipelineDefinitionRegistry(List.of(
					new PipelineVersionDefinition(definition, VersionApplicability.from(1))));
			var service = new ProjectionMaterializationService<>(metadata, transactions, writer,
					Clock.systemUTC(), definitions);

			assertThrows(RuntimeException.class, () -> service.materialize(identity, reconstructed));
			assertEquals(0, readJdbc.queryForObject("select count(*) from pocoma_read.pot_version_metadata",
					Integer.class));
			assertEquals(0, readJdbc.queryForObject("select count(*) from pocoma_read.pot_projection_snapshots",
					Integer.class));
			assertEquals(0, readJdbc.queryForObject("select count(*) from pocoma_read.pot_projection_user_index",
					Integer.class));
			assertTrue(metadata.findArtifact(identity).isEmpty());
			assertTrue(metadata.findHead(generation).isEmpty());
		});
	}

	@Test
	void failureAfterArtifactDescriptorAndBeforeHeadRollsBackEverything() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort delegate = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			createTestArtifactTable(readJdbc);
			ProjectionGenerationIdentity generation = generation();
			ProjectionIdentity identity = identity(generation, 71);
			ProjectionMetadataPort failingBeforeHead = new ProjectionMetadataPort() {
				@Override public void lock(ProjectionIdentity value) { delegate.lock(value); }
				@Override public java.util.Optional<ProjectionArtifactDescriptor> findArtifact(ProjectionIdentity value) { return delegate.findArtifact(value); }
				@Override public java.util.Optional<com.kartaguez.pocoma.domain.projection.ProjectionFailure> findFailure(ProjectionIdentity value) { return delegate.findFailure(value); }
				@Override public void insertArtifact(ProjectionArtifactDescriptor value) { delegate.insertArtifact(value); }
				@Override public void insertFailure(com.kartaguez.pocoma.domain.projection.ProjectionFailure value) { delegate.insertFailure(value); }
				@Override public com.kartaguez.pocoma.domain.projection.ProjectionHead advanceHead(ProjectionGenerationIdentity value, long version, Instant at) { throw new ExpectedRollback(); }
				@Override public java.util.Optional<com.kartaguez.pocoma.domain.projection.ProjectionHead> findHead(ProjectionGenerationIdentity value) { return delegate.findHead(value); }
				@Override public void recordViolation(com.kartaguez.pocoma.domain.projection.ProjectionInvariantViolation value) { delegate.recordViolation(value); }
			};
			var service = materializationService(failingBeforeHead, transactions, readJdbc);

			assertThrows(ExpectedRollback.class, () -> service.materialize(identity, "rollback-before-head"));
			assertEquals(0, readJdbc.queryForObject(
					"select count(*) from pocoma_read.test_projection_artifacts where pot_version=71", Integer.class));
			assertTrue(delegate.findArtifact(identity).isEmpty());
			assertTrue(delegate.findHead(generation).isEmpty());
		});
	}

	@Test
	void failureAfterFirstPotFragmentLeavesNoVisibleMaterialization() {
		primaryAndReadContextRunner().run(context -> {
			ProjectionMetadataPort metadata = context.getBean(ProjectionMetadataPort.class);
			ReadStoreTransactionRunner transactions = context.getBean(ReadStoreTransactionRunner.class);
			JdbcOperations readJdbc = context.getBean("readStoreJdbcOperations", JdbcOperations.class);
			ProjectionGenerationIdentity generation = new ProjectionGenerationIdentity(new ProjectionType("READ_POT"),
					new PipelineDefinition(PipelineId.of("read-pot"), 1), PotId.of(UUID.randomUUID()));
			ProjectionIdentity identity = new ProjectionIdentity(generation, 8);
			ProjectionArtifactDescriptor descriptor = new ProjectionArtifactDescriptor(ProjectionArtifactId.random(),
					identity, digestOf("partial"), Instant.parse("2026-01-01T00:00:00Z"));

			assertThrows(ExpectedRollback.class, () -> transactions.run(() -> {
				metadata.insertArtifact(descriptor);
				readJdbc.update("insert into pocoma_read.pot_projection_snapshots "
						+ "(artifact_id,projection_type,pipeline_id,pipeline_version,pot_id,pot_version,status,label,creator_id) "
						+ "values (?,?,?,?,?,?,?,?,?)", descriptor.artifactId().value(), "READ_POT", "read-pot", 1,
						generation.potId().value(), 8, "ACTIVE", "Partial", UUID.randomUUID());
				readJdbc.update("insert into pocoma_read.pot_projection_shareholders "
						+ "(artifact_id,shareholder_id,ordinal,name,weight_numerator,weight_denominator,user_id,deleted) "
						+ "values (?,?,0,'Partial shareholder',1,1,null,false)",
						descriptor.artifactId().value(), UUID.randomUUID());
				throw new ExpectedRollback();
			}));

			assertTrue(metadata.findArtifact(identity).isEmpty());
			assertEquals(0, readJdbc.queryForObject("select count(*) from pocoma_read.pot_projection_snapshots",
					Integer.class));
			assertEquals(0, readJdbc.queryForObject("select count(*) from pocoma_read.pot_projection_shareholders",
					Integer.class));
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
			var materialization = materializationService(metadata, transactions, readJdbc);
			var failures = new ProjectionFailureService(metadata, transactions, registry(1, 100));
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
		}, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), registry(1, 100));
	}

	private static PipelineDefinitionRegistry registry(long from, long through) {
		return new PipelineDefinitionRegistry(java.util.List.of(new PipelineVersionDefinition(
				new PipelineDefinition(PipelineId.of("READ_TEST"), 2),
				VersionApplicability.between(from, through))));
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

	private static void materializeMinimalPot(
			ProjectionMaterializationService<ReconstructedPotProjection> service,
			PipelineDefinition definition,
			PotId potId,
			long version,
			UserId userId,
			Instant createdAt) {
		var identity = new ProjectionIdentity(new ProjectionGenerationIdentity(
				new ProjectionType("READ_POT"), definition, potId), version);
		var projection = new PotProjection(
				identity, PotProjectionStatus.ACTIVE, "Pot " + potId.value(), userId, List.of(), List.of());
		assertInstanceOf(ProjectionMaterializationResult.Created.class, service.materialize(
				identity, new ReconstructedPotProjection(
						projection, new PotVersionMetadata(potId, version, createdAt))));
	}

	private static final class ExpectedRollback extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
