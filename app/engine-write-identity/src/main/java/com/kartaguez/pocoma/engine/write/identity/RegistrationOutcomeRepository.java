package com.kartaguez.pocoma.engine.write.identity;

import com.kartaguez.pocoma.engine.write.identity.RegistrationOutcome;
import java.util.UUID;
import java.util.Optional;

public interface RegistrationOutcomeRepository {
    Optional<RegistrationOutcome> find(UUID requestId);
    void insert(RegistrationOutcome outcome);
}
