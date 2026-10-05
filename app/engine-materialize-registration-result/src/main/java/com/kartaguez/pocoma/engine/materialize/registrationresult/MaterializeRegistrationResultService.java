package com.kartaguez.pocoma.engine.materialize.registrationresult;

import java.util.Objects;
import java.util.UUID;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcome;
import com.kartaguez.pocoma.engine.read.registrationresult.ImmutableRegistrationResult;
import com.kartaguez.pocoma.engine.read.registrationresult.PublishedRegistrationResult;
import com.kartaguez.pocoma.engine.read.registrationresult.RegistrationResultStore;

/** Materializes the durable decision without consulting current Binding state. */
public final class MaterializeRegistrationResultService {
    private final RegistrationResultSourcePort source;
    private final RegistrationResultStore results;

    public MaterializeRegistrationResultService(RegistrationResultSourcePort source, RegistrationResultStore results) {
        this.source = Objects.requireNonNull(source);
        this.results = Objects.requireNonNull(results);
    }

    public void materialize(UUID discoveredRequestId) {
        Objects.requireNonNull(discoveredRequestId);
        RegistrationResultSource loaded = Objects.requireNonNull(source.reload(discoveredRequestId));
        RegistrationRequest request = Objects.requireNonNull(loaded.request(), "Terminal Registration has no Request");
        RegistrationOutcome outcome = Objects.requireNonNull(loaded.outcome(), "Terminal Registration Outcome missing");
        if (!discoveredRequestId.equals(request.requestId()) || !request.requestId().equals(outcome.requestId())) {
            throw new IllegalStateException("Registration Request and Outcome disagree");
        }
        PublishedRegistrationResult published = switch (outcome) {
            case RegistrationOutcome.Registered registered -> new PublishedRegistrationResult.Registered(registered.requestId(), registered.userId(), registered.bindingId());
            case RegistrationOutcome.Rejected rejected -> new PublishedRegistrationResult.Rejected(rejected.requestId());
        };
        results.ensureResult(new ImmutableRegistrationResult(request.requesterExternalIdentity(), published));
    }
}
