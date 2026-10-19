package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

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
    @Override public void append(UUID requestId, PocomaUserId userId) {
        PrimaryWriteParticipation.require(jdbc);
        jdbc.update("insert into user_created_facts(event_id,request_id,user_id,recorded_at) values (?,?,?,now())",
                UUID.randomUUID(), requestId, userId.value());
    }
}
