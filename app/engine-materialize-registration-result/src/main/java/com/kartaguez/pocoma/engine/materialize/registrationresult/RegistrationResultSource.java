package com.kartaguez.pocoma.engine.materialize.registrationresult;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcome;

/** The durable Request and Outcome, reloaded together for direct materialization. */
public record RegistrationResultSource(RegistrationRequest request, RegistrationOutcome outcome) {}
