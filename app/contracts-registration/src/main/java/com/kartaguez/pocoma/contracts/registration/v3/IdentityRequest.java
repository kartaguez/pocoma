package com.kartaguez.pocoma.contracts.registration.v3;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentity;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable admitted intention. No destination UserId, WRITE lookup or provider validation here. */
public record IdentityRequest(
        UUID requestId,
        ExternalIdentity requesterEui,
        Operation operation,
        BindingToken bindingToken,
        Instant createdAt,
        AdmissionContext admission) {
    public IdentityRequest {
        Objects.requireNonNull(requestId, "requestId");
        ExternalUserIdentity.compareKeys(requesterEui, requesterEui);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(admission, "admission");
        if (operation instanceof Registration ? bindingToken != null : bindingToken == null)
            throw new IllegalArgumentException("Only Registration omits the initiator token");
        ExternalIdentity target = targetOf(requesterEui, operation);
        if (!admission.initiator().equals(requesterEui) || !admission.target().equals(target))
            throw new IllegalArgumentException("Admission context does not describe this intention");
    }

    public ExternalIdentity target() {
        return targetOf(requesterEui, operation);
    }

    private static ExternalIdentity targetOf(ExternalIdentity requester, Operation operation) {
        return switch (operation) {
            case Registration ignored -> requester;
            case Link link -> link.target();
            case Unlink unlink -> unlink.target();
        };
    }

    @Override
    public String toString() {
        return "IdentityRequest[requestId=" + requestId + ", operation="
                + operation.getClass().getSimpleName() + "]";
    }

    public sealed interface Operation permits Registration, Link, Unlink {}

    /** Registration always targets the authenticated requester. */
    public record Registration() implements Operation {}

    public record Link(ExternalIdentity target) implements Operation {
        public Link {
            ExternalUserIdentity.compareKeys(target, target);
        }
    }

    public record Unlink(ExternalIdentity target) implements Operation {
        public Unlink {
            ExternalUserIdentity.compareKeys(target, target);
        }
    }

    /**
     * Captured validator provenance, not a Consent entity. The trusted admission boundary binds
     * the fingerprint to operation, E1/E2, declared token and request correlation. Provider proof
     * formats/verification and external admission are L5; retry never revalidates that grant.
     */
    public record AdmissionContext(
            String validator, String contractVersion,
            ExternalIdentity initiator, ExternalIdentity target,
            String intentionFingerprint, String grantOrCorrelationReference,
            Instant validatedAt, Map<String, String> attestations, Origin origin) {
        public AdmissionContext {
            requireText(validator);
            requireText(contractVersion);
            ExternalUserIdentity.compareKeys(initiator, target);
            requireText(intentionFingerprint);
            requireText(grantOrCorrelationReference);
            Objects.requireNonNull(validatedAt, "validatedAt");
            attestations = Map.copyOf(attestations);
            Objects.requireNonNull(origin, "origin");
        }

        @Override
        public String toString() {
            return "AdmissionContext[REDACTED]";
        }

        private static void requireText(String value) {
            if (Objects.requireNonNull(value).isBlank())
                throw new IllegalArgumentException("Admission provenance must not be blank");
        }
    }

    public enum Origin {
        CONTROLLED_INTERNAL,
        EXTERNAL_AUTHORIZATION_SERVER
    }
}
