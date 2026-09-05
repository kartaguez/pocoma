package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;

class JpaPotCommandEventAppendAdapterTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void appendsOneBatchAndReturnsOrderedEventArtifacts() {
		JpaBusinessEventOutboxRepository repository = mock(JpaBusinessEventOutboxRepository.class);
		var adapter = new JpaPotCommandEventAppendAdapter(repository,
				new BusinessEventRecordMapper(new ObjectMapper()), Clock.fixed(NOW, ZoneOffset.UTC));
		PotId potId = PotId.of(UUID.randomUUID());

		var artifacts = adapter.appendAll(List.of(
				new PotCreatedEvent(potId, 1), new PotDeletedEvent(potId, 2)));

		ArgumentCaptor<List<JpaBusinessEventOutboxEntity>> persisted = ArgumentCaptor.forClass(List.class);
		verify(repository, times(1)).saveAllAndFlush(persisted.capture());
		assertEquals(List.of("PotCreatedEvent", "PotDeletedEvent"),
				persisted.getValue().stream().map(entity -> entity.toEnvelope().eventType()).toList());
		assertEquals(List.of("PotCreatedEvent", "PotDeletedEvent"),
				artifacts.stream().map(artifact -> artifact.type()).toList());
		assertEquals(List.of(1L, 2L), artifacts.stream()
				.map(artifact -> artifact.subject().orElseThrow().version()).toList());
		assertEquals(NOW, artifacts.getFirst().createdAt());
	}

	@Test
	void unsupportedEventFamilyIsATerminalTechnicalInvariantAndPersistsNothing() {
		JpaBusinessEventOutboxRepository repository = mock(JpaBusinessEventOutboxRepository.class);
		var adapter = new JpaPotCommandEventAppendAdapter(repository,
				new BusinessEventRecordMapper(new ObjectMapper()), Clock.systemUTC());

		assertThrows(CommandExecutionInvariantViolationException.class,
				() -> adapter.appendAll(List.of(new ForeignEvent())));
		verify(repository, times(0)).saveAllAndFlush(org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void appendRequiresTheWinningCallerTransaction() throws Exception {
		Method method = JpaPotCommandEventAppendAdapter.class.getMethod("appendAll", List.class);
		assertEquals(Propagation.MANDATORY, method.getAnnotation(Transactional.class).propagation());
	}

	private record ForeignEvent() implements BusinessEvent {
	}
}
