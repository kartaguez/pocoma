package com.kartaguez.pocoma.engine.registration;

import java.util.Objects;
import java.util.UUID;

/** Materializes the durable decision without consulting current Binding state. */
public final class MaterializeRegistrationResultService {
    private final RegistrationRequestStore requests;
    private final RegistrationOutcomeStore outcomes;
    private final RegistrationResultStore results;

    public MaterializeRegistrationResultService(RegistrationRequestStore requests,
            RegistrationOutcomeStore outcomes, RegistrationResultStore results) {
        this.requests = Objects.requireNonNull(requests);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.results = Objects.requireNonNull(results);
    }

    public void materialize(UUID discoveredRequestId) {
        Objects.requireNonNull(discoveredRequestId);
        RegistrationRequest request = requests.find(discoveredRequestId)
                .orElseThrow(() -> new IllegalStateException("Terminal Registration has no Request"));
        RegistrationOutcome outcome = outcomes.find(discoveredRequestId)
                .orElseThrow(() -> new IllegalStateException("Terminal Registration Outcome missing"));
        if (!discoveredRequestId.equals(request.requestId()) || !request.requestId().equals(outcome.requestId())) {
            throw new IllegalStateException("Registration Request and Outcome disagree");
        }
        results.ensureResult(new ImmutableRegistrationResult(request.requesterExternalIdentity(), outcome));
    }
}
