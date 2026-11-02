package com.kartaguez.pocoma.domain.projection.v3;

import java.util.Objects;

/** Equality includes the complete typed content as well as the entire business key. */
public record SubobjectVersion(SubobjectKey key, SubobjectContent content) {
    public SubobjectVersion {
        Objects.requireNonNull(key);
        Objects.requireNonNull(content);
        if (key.kind() != content.kind() || !key.potId().equals(content.potId())
                || !key.objectId().equals(content.objectId()))
            throw new IllegalArgumentException("Subobject identity/kind mismatch");
    }
}
