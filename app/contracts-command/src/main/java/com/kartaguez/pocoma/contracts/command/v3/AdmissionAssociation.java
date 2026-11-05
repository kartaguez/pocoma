package com.kartaguez.pocoma.contracts.command.v3;

import java.time.Instant;
import java.util.*;

/** Immutable S-03 metadata. Fingerprints are protected; raw keys/intentions are not stored here. */
public record AdmissionAssociation(
        UUID requestId,
        String category,
        String operation,
        int canonicalVersion,
        String lookupKeyId,
        String comparisonKeyId,
        byte[] scopeFingerprint,
        byte[] intentFingerprint,
        Instant createdAt) {
    public AdmissionAssociation {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(createdAt);
        if (!Set.of("COMMAND", "IDENTITY").contains(category)
                || canonicalVersion < 1
                || lookupKeyId == null
                || lookupKeyId.isBlank()
                || comparisonKeyId == null
                || comparisonKeyId.isBlank()
                || scopeFingerprint.length != 32
                || intentFingerprint.length != 32)
            throw new IllegalArgumentException("Invalid association");
        scopeFingerprint = scopeFingerprint.clone();
        intentFingerprint = intentFingerprint.clone();
    }

    @Override
    public byte[] scopeFingerprint() {
        return scopeFingerprint.clone();
    }

    @Override
    public byte[] intentFingerprint() {
        return intentFingerprint.clone();
    }

    @Override
    public String toString() {
        return "AdmissionAssociation[requestId=" + requestId + "]";
    }

    public interface Store {
        Optional<AdmissionAssociation> find(byte[] scopeFingerprint);

        /** Caller owns the physical WRITE unit. Empty means another transaction won. */
        boolean reserve(AdmissionAssociation association);
    }
}
