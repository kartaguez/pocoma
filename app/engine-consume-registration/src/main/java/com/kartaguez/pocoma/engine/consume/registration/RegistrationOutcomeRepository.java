package com.kartaguez.pocoma.engine.consume.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;
import java.util.UUID;
import java.util.Optional;

public interface RegistrationOutcomeRepository {
    Optional<RegistrationOutcome> find(UUID requestId);
    void insert(RegistrationOutcome outcome);
}
