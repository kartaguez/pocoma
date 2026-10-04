package com.kartaguez.pocoma.engine.read.registrationresult;

import java.util.Optional;
import java.util.UUID;

public interface RegistrationResultStore {
    void ensureResult(ImmutableRegistrationResult result);
    Optional<ImmutableRegistrationResult> find(UUID requestId);
}
