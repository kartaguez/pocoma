package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionArtifact;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionConflictException;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionIdentity;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionPersistenceResult;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JpaImmutableBalanceProjectionAdapterPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaImmutableBalanceProjectionAdapter projections;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	private final PotId potId = PotId.of(UUID.randomUUID());
	private final ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

	@BeforeEach
	void clean() {
		jdbc.update("delete from balance_projection_entries");
		jdbc.update("delete from balance_projection_artifacts");
		jdbc.update("delete from pot_global_versions");
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, ?)", potId.value(), 50L);
	}

	@Test
	void keepsPipelineVersionsDistinctAndAdoptsIdenticalArtifacts() {
		var v1 = artifact(1, Fraction.of(3, 2));
		var v2 = artifact(2, Fraction.of(3, 2));

		var createdV1 = inTransaction(() -> projections.createOrVerify(v1));
		var adoptedV1 = inTransaction(() -> projections.createOrVerify(v1));
		var createdV2 = inTransaction(() -> projections.createOrVerify(v2));

		assertInstanceOf(BalanceProjectionPersistenceResult.Created.class, createdV1);
		assertInstanceOf(BalanceProjectionPersistenceResult.AlreadyPresent.class, adoptedV1);
		assertEquals(createdV1.reference().projectionId(), adoptedV1.reference().projectionId());
		assertNotEquals(createdV1.reference().projectionId(), createdV2.reference().projectionId());
		assertEquals(2, jdbc.queryForObject("select count(*) from balance_projection_artifacts", Integer.class));
		assertEquals(2, jdbc.queryForObject("select count(*) from balance_projection_entries", Integer.class));
	}

	@Test
	void conflictingContentIsATerminalInvariantViolationAndDoesNotMutateTheWinner() {
		inTransaction(() -> projections.createOrVerify(artifact(2, Fraction.of(3, 2))));

		assertThrows(BalanceProjectionConflictException.class,
				() -> inTransaction(() -> projections.createOrVerify(artifact(2, Fraction.of(7, 3)))));

		assertEquals(3L, jdbc.queryForObject("select value_numerator from balance_projection_entries", Long.class));
		assertEquals(2L, jdbc.queryForObject("select value_denominator from balance_projection_entries", Long.class));
	}

	@Test
	void writingOutsideTheExecutionTransactionIsRejected() {
		assertThrows(IllegalTransactionStateException.class,
				() -> projections.createOrVerify(artifact(2, Fraction.of(1, 1))));
	}

	private BalanceProjectionArtifact artifact(int pipelineVersion, Fraction value) {
		var identity = new BalanceProjectionIdentity("POT_BALANCES",
				new PipelineDefinition(PipelineId.of("balance-projection"), pipelineVersion), potId, 42);
		return new BalanceProjectionArtifact(identity,
				Map.of(shareholderId, new Balance(shareholderId, value)));
	}

	private <T> T inTransaction(java.util.function.Supplier<T> operation) {
		return new TransactionTemplate(transactionManager).execute(status -> operation.get());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(JpaImmutableBalanceProjectionAdapter.class)
	static class TestApplication {}
}
