package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.outbox.JpaBusinessEventOutboxRepository;

@SpringBootTest(properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false" })
@Testcontainers
class JpaEventPortPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaEventPort events;
	@Autowired private JpaBusinessEventOutboxAdapter outbox;
	@Autowired private JpaBusinessEventOutboxRepository repository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private TrackingObjectMapper objectMapper;

	@BeforeEach
	void cleanEvents() {
		repository.deleteAll();
		objectMapper.reset();
	}

	@Test
	void authoritativeReadRejectsAnInvalidPayload() {
		var potId = java.util.UUID.randomUUID();
		var createdAt = Instant.parse("2026-09-01T10:00:00Z");
		var entity = repository.saveAndFlush(new JpaBusinessEventOutboxEntity(
				"POT_SHAREHOLDERS_ADDED", potId, potId, 7, "{not-json", null, null, createdAt));

		assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactionManager)
				.execute(status -> events.findById(entity.id()).orElseThrow()));
	}

	@Test
	void authoritativeReadRequiresATransactionAndDeserializesOnce() {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 5));
		var stored = repository.findAll().getFirst();
		var envelope = stored.toEnvelope();
		assertEquals(1, objectMapper.writes.get());
		assertEquals(0, objectMapper.reads.get());

		assertThrows(IllegalTransactionStateException.class, () -> events.findById(stored.id()));

		var authoritative = new TransactionTemplate(transactionManager)
				.execute(status -> events.findById(stored.id()).orElseThrow());
		assertEquals(stored.id(), authoritative.eventId());
		assertEquals(envelope.potId(), authoritative.event().potId());
		assertEquals(envelope.version(), authoritative.event().version());
		assertEquals(envelope.createdAt(), authoritative.recordedAt());
		assertEquals(1, objectMapper.reads.get());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = JpaBusinessEventOutboxEntity.class)
	@EnableJpaRepositories(basePackageClasses = JpaBusinessEventOutboxRepository.class)
	@Import({JpaEventPort.class, JpaBusinessEventOutboxAdapter.class})
	static class TestApplication {
		@Bean TrackingObjectMapper objectMapper() { return new TrackingObjectMapper(); }
	}

	static final class TrackingObjectMapper extends ObjectMapper {
		private final AtomicInteger reads = new AtomicInteger();
		private final AtomicInteger writes = new AtomicInteger();
		@Override public JsonNode readTree(String content) throws JsonProcessingException {
			reads.incrementAndGet();
			return super.readTree(content);
		}
		@Override public String writeValueAsString(Object value) throws JsonProcessingException {
			writes.incrementAndGet();
			return super.writeValueAsString(value);
		}
		void reset() { reads.set(0); writes.set(0); }
	}
}
