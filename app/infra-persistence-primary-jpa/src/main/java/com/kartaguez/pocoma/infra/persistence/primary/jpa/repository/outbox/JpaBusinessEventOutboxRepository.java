package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.outbox;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

public interface JpaBusinessEventOutboxRepository extends JpaRepository<JpaBusinessEventOutboxEntity, UUID> {}
