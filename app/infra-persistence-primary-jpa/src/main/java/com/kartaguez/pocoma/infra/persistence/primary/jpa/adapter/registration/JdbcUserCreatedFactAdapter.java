package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.EventFamily;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.consume.registration.UserCreatedFactPort;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.PrimaryWriteParticipation;

@Component
public final class JdbcUserCreatedFactAdapter implements UserCreatedFactPort {
    private final JdbcTemplate jdbc;
    public JdbcUserCreatedFactAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public EventRef append(UUID requestId, PocomaUserId userId) {
        return PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), () -> {
        PrimaryWriteParticipation.require(jdbc);
        UUID eventId = UUID.randomUUID();
        int rows = jdbc.update("insert into user_created_facts(event_id,request_id,user_id,recorded_at) values (?,?,?,now())",
                eventId, requestId, userId.value());
        if (rows != 1) throw new IllegalStateException("UserCreated must insert exactly one row");
        return new EventRef(EventFamily.USER_CREATED_FACT, eventId);

        });
    }
}
