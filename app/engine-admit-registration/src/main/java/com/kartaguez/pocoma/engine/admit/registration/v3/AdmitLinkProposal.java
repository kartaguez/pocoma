package com.kartaguez.pocoma.engine.admit.registration.v3;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.contracts.registration.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Two authenticated acts, one physical admission. No Identity business authority dependency. */
public final class AdmitLinkProposal {
    public interface Store extends AdmissionAssociation.Store {
        Instant now();
        Optional<LinkProposal> proposalByScope(byte[] scope);
        Optional<LinkProposal> proposal(UUID id, boolean lock);
        boolean insertProposal(LinkProposal proposal);
        void insertAcceptedLink(IdentityRequest request);
        void accept(UUID id, Instant acceptedAt);
    }
    /** Public allowlist: no occurrence token, provenance or UserId. */
    public record Summary(UUID linkRequestId, com.kartaguez.pocoma.domain.useridentity.ExternalIdentity initiator,
            com.kartaguez.pocoma.domain.useridentity.ExternalIdentity target, String action,
            Instant createdAt, Instant expiresAt, String state, UUID requestId,
            Long potCount, Instant potCountAsOf) {}
    public record Created(UUID linkRequestId, Instant expiresAt) {}
    public record Accepted(UUID linkRequestId, UUID requestId) {}
    private final Store store;
    private final AttemptTransactions transactions;
    private final AdmissionProtection protection;
    private final Clock clock;
    private final Duration ttl;
    private final int textBytes;
    public AdmitLinkProposal(Store store, AttemptTransactions transactions, AdmissionProtection protection,
            Clock clock, Duration ttl, int textBytes) {
        this.store = Objects.requireNonNull(store); this.transactions = Objects.requireNonNull(transactions);
        this.protection = Objects.requireNonNull(protection); this.clock = Objects.requireNonNull(clock);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(1)) > 0 || textBytes < 1)
            throw new IllegalArgumentException("Invalid Link limits");
        this.ttl = ttl; this.textBytes = textBytes;
    }
    private static void authenticated(AuthenticatedExternalPrincipal p, Instant now) {
        if (p == null || !now.isBefore(p.expiresAt())) throw new AdmissionFailure(401);
    }
    public Created create(AuthenticatedExternalPrincipal p, String key, LinkProposalInput input) {
        authenticated(p, clock.instant());
        AdmissionFailure.require(p.identity().equals(input.initiator()));
        byte[] scope = protection.scope(p.issuer(), p.subject(), Optional.empty(),
                "IDENTITY", "LinkProposal", key);
        return safe(() -> transactions.write(() -> {
            var old = store.proposalByScope(scope);
            Instant now = store.now();
            authenticated(p, now);
            if (old.isPresent()) return replay(old.get(), input);
            input.validateCurrent(textBytes);
            var proposal = new LinkProposal(UUID.randomUUID(), p.identity(), input.target(), input.token(),
                    p.authenticatedAt(), p.issuedAt(), p.expiresAt(), now, now.plus(ttl), 1,
                    protection.lookupId(), protection.currentId(), scope,
                    protection.intent(protection.currentId(), 1, input.canonical(1)), null, null);
            if (!store.insertProposal(proposal)) {
                authenticated(p, store.now());
                return replay(store.proposalByScope(scope).orElseThrow(() -> new AdmissionFailure(503)), input);
            }
            return new Created(proposal.linkRequestId(), proposal.expiresAt());
        }));
    }
    private Created replay(LinkProposal p, LinkProposalInput input) {
        var a = new AdmissionAssociation(p.linkRequestId(), "IDENTITY", "LinkProposal", p.canonicalVersion(),
                p.lookupKeyId(), p.comparisonKeyId(), p.scopeFingerprint(), p.intentFingerprint(), p.createdAt());
        if (!protection.matches(a, input.canonical(p.canonicalVersion()))) throw new AdmissionFailure(409);
        return new Created(p.linkRequestId(), p.expiresAt());
    }
    public Summary get(AuthenticatedExternalPrincipal actor, UUID id) {
        authenticated(actor, clock.instant());
        return safe(() -> transactions.read(c -> {
            var p = store.proposal(id, false).orElseThrow(() -> new AdmissionFailure(404));
            if (!actor.identity().equals(p.initiator()) && !actor.identity().equals(p.target()))
                throw new AdmissionFailure(404);
            Instant now = store.now();
            authenticated(actor, now);
            if (p.identityRequestId() == null && !now.isBefore(p.expiresAt())) throw new AdmissionFailure(410);
            return new Summary(id, p.initiator(), p.target(), "LINK_TO_INITIATOR_ACCOUNT", p.createdAt(),
                    p.expiresAt(), p.identityRequestId() == null ? "PENDING" : "ACCEPTED",
                    p.identityRequestId(), null, null);
        }));
    }
    public Accepted accept(AuthenticatedExternalPrincipal actor, UUID id) {
        authenticated(actor, clock.instant());
        return safe(() -> transactions.write(() -> {
            var p = store.proposal(id, true).orElseThrow(() -> new AdmissionFailure(404));
            if (!actor.identity().equals(p.target())) throw new AdmissionFailure(404);
            Instant now = store.now(); // PostgreSQL clock_timestamp AFTER the row lock.
            authenticated(actor, now);
            if (p.identityRequestId() != null) return new Accepted(id, p.identityRequestId());
            if (!now.isBefore(p.expiresAt())) throw new AdmissionFailure(410);
            var input = LinkProposalInput.decode(Map.of("bindingToken", p.bindingToken().value(),
                    "target", Map.of("issuer", p.target().issuer(), "subject", p.target().subject())), p.initiator());
            replay(p, input); // Stored version/key must still be understood; never recanonicalize silently.
            var association = new AdmissionAssociation(id, "IDENTITY", "Link", p.canonicalVersion(),
                    p.lookupKeyId(), p.comparisonKeyId(),
                    protection.scope(p.initiator().issuer(), p.initiator().subject(), Optional.empty(),
                            "IDENTITY", "Link", id.toString()), p.intentFingerprint(), now);
            if (!store.reserve(association)) throw new AdmissionFailure(503);
            var attestations = Map.of("acceptedIssuer", actor.issuer(), "acceptedSubject", actor.subject(),
                    "authenticatedAt", actor.authenticatedAt().toString(), "issuedAt", actor.issuedAt().toString(),
                    "expiresAt", actor.expiresAt().toString());
            var context = new IdentityRequest.AdmissionContext("pocoma-v3-link-post", "1", p.initiator(),
                    p.target(), HexFormat.of().formatHex(p.intentFingerprint()), id.toString(), now,
                    attestations, IdentityRequest.Origin.AUTHENTICATED_HTTP);
            store.accept(id, now);
            store.insertAcceptedLink(new IdentityRequest(id, p.initiator(), new IdentityRequest.Link(p.target()),
                    p.bindingToken(), now, context));
            return new Accepted(id, id);
        }));
    }
    private static <T> T safe(Supplier<T> action) {
        try { return action.get(); }
        catch (AdmissionFailure failure) { throw failure; }
        catch (AttemptTransactions.Failure failure) {
            if (failure.getCause() instanceof AdmissionFailure known) throw known;
            throw new AdmissionFailure(503);
        } catch (RuntimeException failure) { throw new AdmissionFailure(503); }
    }
}
