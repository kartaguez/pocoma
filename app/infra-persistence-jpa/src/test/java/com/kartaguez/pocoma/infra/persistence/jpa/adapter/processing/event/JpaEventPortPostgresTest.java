package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
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
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaEventConsumptionDiscoveryRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;

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
	@Autowired private JpaEventConsumptionDiscoveryAdapter discovery;
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
	void structurallyDiscoversAnEventWhosePayloadCannotBeDeserialized() {
		var potId = java.util.UUID.randomUUID();
		var createdAt = Instant.parse("2026-09-01T10:00:00Z");
		var entity = repository.saveAndFlush(new JpaBusinessEventOutboxEntity(
				"PotShareholdersAddedEvent", potId, potId, 7, "{not-json", null, null, createdAt));

		var candidate = discovery.findNextEligibleCandidate(new PipelineDefinition(PipelineId.of("balances"), 1),
				WorkerSegment.single(), createdAt.plusSeconds(1), Optional.empty()).orElseThrow();

		assertEquals(entity.id(), candidate.eventId());
		assertEquals(PotId.of(potId), candidate.potId());
		assertEquals(7, candidate.version());
		assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactionManager)
				.execute(status -> events.findById(candidate.eventId()).orElseThrow()));
	}

	@Test
	void candidateReadEndsBeforeAuthoritativeMandatoryReload() {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 5));
		assertEquals(1, objectMapper.writes.get());
		assertEquals(0, objectMapper.reads.get());
		var candidate = discovery.findNextEligibleCandidate(new PipelineDefinition(PipelineId.of("balances"), 1),
				WorkerSegment.single(), Instant.now(), Optional.empty()).orElseThrow();
		assertEquals(0, objectMapper.reads.get());

		assertThrows(IllegalTransactionStateException.class, () -> events.findById(candidate.eventId()));

		var authoritative = new TransactionTemplate(transactionManager)
				.execute(status -> events.findById(candidate.eventId()).orElseThrow());
		assertEquals(candidate.eventId(), authoritative.eventId());
		assertEquals(candidate.potId(), authoritative.event().potId());
		assertEquals(candidate.version(), authoritative.event().version());
		assertEquals(candidate.createdAt(), authoritative.recordedAt());
		assertEquals(1, objectMapper.reads.get());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {JpaBusinessEventOutboxEntity.class, JpaConsumptionSlotEntity.class})
	@EnableJpaRepositories(basePackageClasses = JpaBusinessEventOutboxRepository.class)
	@Import({JpaEventPort.class, JpaEventConsumptionDiscoveryAdapter.class,
			JpaEventConsumptionDiscoveryRepository.class, JpaBusinessEventOutboxAdapter.class})
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
