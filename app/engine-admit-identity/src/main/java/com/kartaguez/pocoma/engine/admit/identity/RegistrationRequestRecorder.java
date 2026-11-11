package com.kartaguez.pocoma.engine.admit.identity;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import java.util.UUID;
import java.util.Optional;

public interface RegistrationRequestRecorder {
    void insert(RegistrationRequest request);
}
