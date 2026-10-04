package com.kartaguez.pocoma.infra.persistence.jpa.adapter.registration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;
import com.kartaguez.pocoma.engine.registration.RegistrationOutcomeStore;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcomeRepository;

@Component
public final class JdbcRegistrationOutcomeStore implements RegistrationOutcomeStore, RegistrationOutcomeRepository {
    private final JdbcTemplate jdbc;
    public JdbcRegistrationOutcomeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<RegistrationOutcome> find(UUID requestId) {
        java.util.List<RegistrationOutcome> found = jdbc.query("select request_id,outcome_type,user_id,binding_id,rejection_code from registration_outcomes where request_id=?",
                (rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    if ("REGISTERED".equals(rs.getString(2))) return new RegistrationOutcome.Registered(id,
                            new PocomaUserId(rs.getObject(3, UUID.class)), new BindingId(rs.getObject(4, UUID.class)));
                    if ("REJECTED".equals(rs.getString(2))
                            && RegistrationOutcome.Rejected.CODE.equals(rs.getString(5))) return new RegistrationOutcome.Rejected(id);
                    throw new IllegalStateException("Invalid RegistrationOutcome shape");
                }, requestId);
        return found.stream().findFirst();
    }
    @Override public void insert(RegistrationOutcome outcome) {
        if (outcome instanceof RegistrationOutcome.Registered registered) {
            jdbc.update("insert into registration_outcomes(request_id,outcome_type,user_id,binding_id,decided_at) values (?,'REGISTERED',?,?,now())",
                    registered.requestId(), registered.userId().value(), registered.bindingId().value());
        } else {
            jdbc.update("insert into registration_outcomes(request_id,outcome_type,rejection_code,decided_at) values (?,'REJECTED',?,now())",
                    outcome.requestId(), RegistrationOutcome.Rejected.CODE);
        }
    }
}
