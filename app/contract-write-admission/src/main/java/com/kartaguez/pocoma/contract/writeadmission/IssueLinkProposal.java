package com.kartaguez.pocoma.contract.writeadmission;

import java.time.Instant;
import java.util.UUID;
import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;

public interface IssueLinkProposal {
    public record Summary(UUID linkRequestId, com.kartaguez.pocoma.domain.identity.ExternalIdentity initiator,
            com.kartaguez.pocoma.domain.identity.ExternalIdentity target, String action,
            Instant createdAt, Instant expiresAt, String state, UUID requestId,
            Long potCount, Instant potCountAsOf) {}
    public record Created(UUID linkRequestId, Instant expiresAt) {}
    public record Accepted(UUID linkRequestId, UUID requestId) {}
    Created create(AuthenticatedExternalPrincipal principal, String key, LinkProposalInput input);
    Summary get(AuthenticatedExternalPrincipal principal, UUID id);
    Accepted accept(AuthenticatedExternalPrincipal principal, UUID id);
}
