package com.kartaguez.pocoma.engine.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;

import java.util.Optional;
import java.util.UUID;

public interface RegistrationOutcomeStore {
    Optional<RegistrationOutcome> find(UUID requestId);
    void insert(RegistrationOutcome outcome);
}
