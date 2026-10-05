package com.kartaguez.pocoma.engine.materialize.registrationresult;

import java.util.UUID;

public interface RegistrationResultSourcePort {
    RegistrationResultSource reload(UUID requestId);
}
