package com.kartaguez.pocoma.engine.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;

import java.util.Optional;
import java.util.UUID;

public interface RegistrationRequestStore {
    void insert(RegistrationRequest request);
    Optional<RegistrationRequest> find(UUID requestId);
}
