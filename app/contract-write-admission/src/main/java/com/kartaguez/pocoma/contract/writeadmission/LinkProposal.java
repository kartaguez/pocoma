package com.kartaguez.pocoma.contract.writeadmission;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.BindingToken;
import java.time.Instant;
import java.util.*;

/** Technical pre-admission state. Never returned directly by HTTP. */
public record LinkProposal(
        UUID linkRequestId, ExternalIdentity initiator, ExternalIdentity target,
        BindingToken bindingToken, Instant authenticatedAt, Instant issuedAt, Instant principalExpiresAt,
        Instant createdAt, Instant expiresAt, int canonicalVersion, String lookupKeyId,
        String comparisonKeyId, byte[] scopeFingerprint, byte[] intentFingerprint,
        Instant acceptedAt, UUID identityRequestId) {
    public LinkProposal {
        Objects.requireNonNull(linkRequestId);
        Objects.requireNonNull(initiator);
        Objects.requireNonNull(target);
        Objects.requireNonNull(bindingToken);
        Objects.requireNonNull(authenticatedAt);
        Objects.requireNonNull(issuedAt);
        Objects.requireNonNull(principalExpiresAt);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        if (initiator.equals(target) || !createdAt.isBefore(expiresAt) || canonicalVersion != 1
                || lookupKeyId == null || lookupKeyId.isBlank()
                || comparisonKeyId == null || comparisonKeyId.isBlank()
                || scopeFingerprint.length != 32 || intentFingerprint.length != 32
                || (acceptedAt == null) != (identityRequestId == null)
                || (identityRequestId != null && (!identityRequestId.equals(linkRequestId)
                    || acceptedAt.isBefore(createdAt) || !acceptedAt.isBefore(expiresAt))))
            throw new IllegalArgumentException("Invalid Link proposal");
        scopeFingerprint = scopeFingerprint.clone();
        intentFingerprint = intentFingerprint.clone();
    }
    @Override public byte[] scopeFingerprint() { return scopeFingerprint.clone(); }
    @Override public byte[] intentFingerprint() { return intentFingerprint.clone(); }
    @Override public String toString() { return "LinkProposal[REDACTED]"; }
}
