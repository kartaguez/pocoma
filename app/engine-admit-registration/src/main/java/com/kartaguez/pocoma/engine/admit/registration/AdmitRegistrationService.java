package com.kartaguez.pocoma.engine.admit.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** Returns only after the request insert transaction has committed. */
public final class AdmitRegistrationService {
    private final RegistrationRequestRecorder requests;
    private final TransactionRunner transactions;
    private final Clock clock;

    public AdmitRegistrationService(RegistrationRequestRecorder requests, TransactionRunner transactions, Clock clock) {
        this.requests = Objects.requireNonNull(requests);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public UUID admit(ExternalIdentity authenticatedIdentity) {
        Objects.requireNonNull(authenticatedIdentity);
        UUID id = UUID.randomUUID();
        transactions.runInTransaction(() -> {
            requests.insert(new RegistrationRequest(id, authenticatedIdentity, "{}", clock.instant()));
            return id;
        });
        return id;
    }

    public UUID admit(AuthenticatedExternalPrincipal principal) {
        return admit(Objects.requireNonNull(principal, "principal must not be null").identity());
    }
}
