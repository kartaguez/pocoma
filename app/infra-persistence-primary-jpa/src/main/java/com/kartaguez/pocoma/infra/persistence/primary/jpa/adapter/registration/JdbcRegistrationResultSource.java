package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import org.springframework.stereotype.Component;
import java.util.UUID;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultSource;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultSourcePort;

@Component
public final class JdbcRegistrationResultSource implements RegistrationResultSourcePort {
    private final JdbcRegistrationRequestStore requests;
    private final JdbcRegistrationOutcomeStore outcomes;

    public JdbcRegistrationResultSource(JdbcRegistrationRequestStore requests, JdbcRegistrationOutcomeStore outcomes) {
        this.requests = requests;
        this.outcomes = outcomes;
    }

    @Override public RegistrationResultSource reload(UUID requestId) {
        return new RegistrationResultSource(
            requests.find(requestId).orElseThrow(() -> new IllegalStateException("Terminal Registration has no Request")),
            outcomes.find(requestId).orElseThrow(() -> new IllegalStateException("Terminal Registration Outcome missing")));
    }
}
