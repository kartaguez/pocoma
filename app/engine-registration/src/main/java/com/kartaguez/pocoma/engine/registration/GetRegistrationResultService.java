package com.kartaguez.pocoma.engine.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public final class GetRegistrationResultService {
    private final RegistrationResultStore results;
    public GetRegistrationResultService(RegistrationResultStore results) { this.results = Objects.requireNonNull(results); }
    public Optional<RegistrationOutcome> get(UUID requestId, ExternalIdentity requester) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(requester);
        return results.find(requestId).filter(result -> result.owner().equals(requester))
                .map(ImmutableRegistrationResult::outcome);
    }
}
