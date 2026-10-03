package com.kartaguez.pocoma.engine.registration;

import java.util.Optional;
import java.util.UUID;

public interface RegistrationRequestStore {
    void insert(RegistrationRequest request);
    Optional<RegistrationRequest> find(UUID requestId);
}
