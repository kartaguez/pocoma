package com.kartaguez.pocoma.engine.consume.registration;

import com.kartaguez.pocoma.port.transaction.PublishedEvents;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcome;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.port.binding.authority.UserAuthorityPort;

/** Runs inside the fenced Consumption Execute transaction. */
public final class ExecuteRegistrationService {
    private final PublishedEvents publishedEvents;
    private final RegistrationRequestReader requests;
    private final RegistrationOutcomeRepository outcomes;
    private final UserAuthorityPort users;
    private final ExternalIdentityBindingPort bindings;
    private final UserCreatedFactPort userFacts;

    public ExecuteRegistrationService(RegistrationRequestReader requests, RegistrationOutcomeRepository outcomes,
            UserAuthorityPort users, ExternalIdentityBindingPort bindings, UserCreatedFactPort userFacts, PublishedEvents publishedEvents) {
        this.publishedEvents = java.util.Objects.requireNonNull(publishedEvents);
        this.requests = Objects.requireNonNull(requests);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.users = Objects.requireNonNull(users);
        this.bindings = Objects.requireNonNull(bindings);
        this.userFacts = Objects.requireNonNull(userFacts);
    }

    public RegistrationOutcome execute(UUID requestId) {
        Objects.requireNonNull(requestId);
        var request = requests.find(requestId).orElseThrow(() -> new IllegalStateException("RegistrationRequest missing"));
        var existing = outcomes.find(requestId);
        if (existing.isPresent()) return existing.orElseThrow();
        PocomaUserId userId = new PocomaUserId(UUID.randomUUID());
        var acquired = bindings.acquireWithInitializer(request.requesterExternalIdentity(), userId, () -> {
            users.create(new User(userId));
            publishedEvents.recordPublishedEvent(userFacts.append(requestId, userId));
        });
        acquired.fact().ifPresent(publishedEvents::recordPublishedEvent);
        var result = acquired.result();
        RegistrationOutcome outcome = result.status() == BindingAcquireResult.Status.ACQUIRED
                ? new RegistrationOutcome.Registered(requestId, userId, result.bindingId())
                : new RegistrationOutcome.Rejected(requestId);
        outcomes.insert(outcome);
        return outcome;
    }
}
