package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.BusinessEventRecordMapper;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

import jakarta.persistence.EntityManager;

/** Best-effort cursor read. It deliberately ignores the legacy global Event processing status. */
@Component
public class JpaEventPort implements EventPort {
	private final EntityManager entityManager;
	private final BusinessEventRecordMapper mapper;

	public JpaEventPort(EntityManager entityManager, ObjectMapper objectMapper) {
		this.entityManager = requireNonNull(entityManager, "entityManager must not be null");
		this.mapper = new BusinessEventRecordMapper(
				requireNonNull(objectMapper, "objectMapper must not be null"));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<RecordedEvent<? extends BusinessEvent>> findById(UUID eventId) {
		requireNonNull(eventId, "eventId must not be null");
		return Optional.ofNullable(entityManager.find(JpaBusinessEventOutboxEntity.class, eventId))
				.map(JpaBusinessEventOutboxEntity::toEnvelope)
				.map(mapper::toRecordedEvent);
	}

}
