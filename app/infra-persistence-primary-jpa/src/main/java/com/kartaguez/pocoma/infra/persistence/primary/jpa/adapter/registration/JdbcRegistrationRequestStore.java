package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.admit.registration.RegistrationRequestRecorder;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationRequestReader;

@Component
public final class JdbcRegistrationRequestStore implements RegistrationRequestRecorder, RegistrationRequestReader {
    private final JdbcTemplate jdbc;
    public JdbcRegistrationRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(RegistrationRequest request) {
        jdbc.update("insert into registration_requests(request_id,issuer,subject,payload,created_at) values (?,?,?,?::jsonb,?)",
                request.requestId(), request.requesterExternalIdentity().issuer(),
                request.requesterExternalIdentity().subject(), request.payload(),
                java.sql.Timestamp.from(request.createdAt()));
    }

    @Override public Optional<RegistrationRequest> find(UUID requestId) {
        return jdbc.query("select request_id,issuer,subject,payload::text,created_at from registration_requests where request_id=?",
                (rs, n) -> new RegistrationRequest(rs.getObject(1, UUID.class),
                        new ExternalIdentity(rs.getString(2), rs.getString(3)), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), requestId).stream().findFirst();
    }
}
