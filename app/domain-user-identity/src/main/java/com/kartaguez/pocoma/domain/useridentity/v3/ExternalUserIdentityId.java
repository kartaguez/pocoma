package com.kartaguez.pocoma.domain.useridentity.v3;

import java.util.Objects;
import java.util.UUID;

/** Stable, opaque Identity authority identifier, independent of the external key. */
public record ExternalUserIdentityId(UUID value) {
    public ExternalUserIdentityId {
        Objects.requireNonNull(value, "value");
    }
}
