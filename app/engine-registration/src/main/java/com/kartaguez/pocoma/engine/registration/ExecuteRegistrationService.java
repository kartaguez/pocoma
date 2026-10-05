package com.kartaguez.pocoma.engine.registration;

import java.util.UUID;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcome;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.port.binding.authority.UserAuthorityPort;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcomeRepository;

/** Temporary legacy-to-TARGET facade; execution policy lives in engine-consume-registration. */
@Deprecated(forRemoval = true)
public final class ExecuteRegistrationService {
    private final com.kartaguez.pocoma.engine.consume.registration.ExecuteRegistrationService target;

    public ExecuteRegistrationService(RegistrationRequestStore requests, RegistrationOutcomeStore outcomes,
            UserAuthorityPort users, ExternalIdentityBindingPort bindings, UserCreatedFactPort userFacts) {
        target = new com.kartaguez.pocoma.engine.consume.registration.ExecuteRegistrationService(
                requests::find, new RegistrationOutcomeRepository() {
                    @Override public java.util.Optional<RegistrationOutcome> find(UUID id) { return outcomes.find(id); }
                    @Override public void insert(RegistrationOutcome outcome) { outcomes.insert(outcome); }
                }, users, bindings, userFacts);
    }

    public RegistrationOutcome execute(UUID requestId) { return target.execute(requestId); }
}
