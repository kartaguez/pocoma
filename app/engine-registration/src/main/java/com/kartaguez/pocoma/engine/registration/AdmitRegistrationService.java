package com.kartaguez.pocoma.engine.registration;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

/** Returns only after the request insert transaction has committed. */
public final class AdmitRegistrationService {
    private final RegistrationRequestStore requests;
    private final TransactionRunner transactions;
    private final Clock clock;

    public AdmitRegistrationService(RegistrationRequestStore requests, TransactionRunner transactions, Clock clock) {
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
}
