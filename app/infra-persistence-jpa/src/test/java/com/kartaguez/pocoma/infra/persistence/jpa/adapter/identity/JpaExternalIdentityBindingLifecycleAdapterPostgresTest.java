package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

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

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingStreamJdbcRepository;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JpaExternalIdentityBindingLifecycleAdapterPostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaExternalIdentityBindingStreamAdapter streams;
	@Autowired private JpaExternalIdentityBindingFactAdapter facts;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("delete from external_identity_binding_facts");
		jdbc.update("delete from external_identities");
		jdbc.update("delete from external_identity_binding_occurrences");
		jdbc.update("delete from external_identity_binding_streams");
		jdbc.update("delete from users");
	}

	@Test
	void createsARevisionZeroStreamIdempotentlyAndRequiresTheCallingTransaction() {
		ExternalIdentity identity = new ExternalIdentity("issuer", "subject");

		inTransaction(() -> {
			streams.createIfAbsent(identity);
			streams.createIfAbsent(identity);
			return null;
		});

		assertEquals(new BindingRevision(0),
				inTransaction(() -> streams.findCurrentRevision(identity)).orElseThrow());
		assertEquals(1, jdbc.queryForObject("select count(*) from external_identity_binding_streams",
				Integer.class));
		assertThrows(IllegalTransactionStateException.class, () -> streams.createIfAbsent(identity));
		assertThrows(IllegalTransactionStateException.class, () -> streams.findCurrentRevision(identity));
	}

	@Test
	void appendsCompleteFactsWithoutMutatingTheBindingAuthority() {
		ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
		PocomaUserId userId = new PocomaUserId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
		BindingId bindingId = new BindingId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
		Instant firstRecordedAt = Instant.parse("2026-10-01T10:15:30Z");
		Instant secondRecordedAt = Instant.parse("2026-10-01T10:16:30Z");
		jdbc.update("insert into users (user_id) values (?)", userId.value());
		inTransaction(() -> { streams.createIfAbsent(identity); return null; });
		jdbc.update("insert into external_identity_binding_occurrences "
				+ "(binding_id, issuer, subject, user_id, attached_revision, created_at) "
				+ "values (?, ?, ?, ?, 1, now())", bindingId.value(), identity.issuer(),
				identity.subject(), userId.value());

		inTransaction(() -> {
			facts.append(new ExternalIdentityAttached(
					UUID.fromString("30000000-0000-0000-0000-000000000001"), identity, userId, bindingId,
					new BindingRevision(1), firstRecordedAt));
			facts.append(new ExternalIdentityDetached(
					UUID.fromString("30000000-0000-0000-0000-000000000002"), identity, userId, bindingId,
					new BindingRevision(2), secondRecordedAt));
			return null;
		});

		assertEquals(2, jdbc.queryForObject("select count(*) from external_identity_binding_facts",
				Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(distinct partition_hash) "
				+ "from external_identity_binding_facts", Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(*) from external_identity_binding_facts "
				+ "where fact_type='ATTACHED' and user_id=? and binding_id=?", Integer.class,
				userId.value(), bindingId.value()));
		assertEquals(1, jdbc.queryForObject("select count(*) from external_identity_binding_facts "
				+ "where fact_type='DETACHED' and user_id=? and binding_id=?", Integer.class,
				userId.value(), bindingId.value()));
		assertEquals(0, jdbc.queryForObject("select count(*) from external_identities", Integer.class));
		assertEquals(new BindingRevision(0),
				inTransaction(() -> streams.findCurrentRevision(identity)).orElseThrow());
		assertThrows(IllegalTransactionStateException.class, () -> facts.append(new ExternalIdentityDetached(
				UUID.randomUUID(), identity, userId, bindingId, new BindingRevision(3), Instant.now())));
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({
			JpaExternalIdentityBindingStreamAdapter.class,
			JpaExternalIdentityBindingFactAdapter.class,
			ExternalIdentityBindingStreamJdbcRepository.class,
			ExternalIdentityBindingFactJdbcRepository.class
	})
	static class TestConfiguration {
	}
}
