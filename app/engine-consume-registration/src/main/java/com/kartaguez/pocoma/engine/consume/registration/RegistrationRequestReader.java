package com.kartaguez.pocoma.engine.consume.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import java.util.UUID;
import java.util.Optional;

public interface RegistrationRequestReader {
    Optional<RegistrationRequest> find(UUID requestId);
}
