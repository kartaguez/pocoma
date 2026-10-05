package com.kartaguez.pocoma.engine.materialize.currentbinding;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;

/** Read of an immutable Binding fact for direct CURRENT_BINDING materialization. */
public interface BindingFactReadPort {
    Optional<ExternalIdentityBindingFact> findByEventId(UUID eventId);
}
