package com.kartaguez.pocoma.engine.admit.identity.v3;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionAssociation;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFields;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.IdentityAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.contract.writeadmission.LinkProposal;
import com.kartaguez.pocoma.contract.writeadmission.LinkProposalInput;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.port.transaction.v3.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.*;

class V3LinkConsentContractTest {
    Instant now = Instant.parse("2026-11-05T10:00:00Z");
    final ExternalIdentity e1 = new ExternalIdentity("issuer", "E1"), e2 = new ExternalIdentity("issuer", "E2");
    LinkProposal proposal;
    IdentityRequest admitted;
    AdmissionAssociation association;
    final AdmissionProtection protection = new HmacAdmissionProtection("lookup", new byte[32], "key", Map.of("key", new byte[32]));
    final AdmitLinkProposal.Store store = new AdmitLinkProposal.Store() {
        public Instant now() { return now; }
        public Optional<LinkProposal> proposalByScope(byte[] scope) {
            return proposal != null && Arrays.equals(scope, proposal.scopeFingerprint()) ? Optional.of(proposal) : Optional.empty();
        }
        public Optional<LinkProposal> proposal(UUID id, boolean lock) { return Optional.ofNullable(proposal).filter(p -> p.linkRequestId().equals(id)); }
        public boolean insertProposal(LinkProposal p) { proposal = p; return true; }
        public void insertAcceptedLink(IdentityRequest r) { admitted = r; }
        public void accept(UUID id, Instant at) {
            var p = proposal;
            proposal = new LinkProposal(id, p.initiator(), p.target(), p.bindingToken(), p.authenticatedAt(), p.issuedAt(),
                    p.principalExpiresAt(), p.createdAt(), p.expiresAt(), p.canonicalVersion(), p.lookupKeyId(),
                    p.comparisonKeyId(), p.scopeFingerprint(), p.intentFingerprint(), at, id);
        }
        public Optional<AdmissionAssociation> find(byte[] scope) { return Optional.ofNullable(association); }
        public boolean reserve(AdmissionAssociation a) { assertNull(association); association = a; return true; }
    };
    final AttemptTransactions tx = new AttemptTransactions() {
        public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) { return action.apply(null); }
        public <T> T write(Supplier<T> action, Guard guard) { return action.get(); }
        public void poison(Throwable cause) { fail(); }
        public void requirePublicationClosed() { fail(); }
    };
    AdmitLinkProposal service() {
        return new AdmitLinkProposal(store, tx, protection, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(10), 4096);
    }
    AuthenticatedExternalPrincipal actor(ExternalIdentity identity) {
        return new AuthenticatedExternalPrincipal(identity.issuer(), identity.subject(), now.minusSeconds(2),
                now.minusSeconds(1), now.plusSeconds(60), Set.of());
    }
    LinkProposalInput input(String issuer, String subject, String token) {
        return LinkProposalInput.decode(Map.of("target", Map.of("issuer", issuer, "subject", subject), "bindingToken", token), e1);
    }
    UUID create() { return service().create(actor(e1), "key", input("issuer", "E2", "SECRET-E1")).linkRequestId(); }
    void status(int status, Runnable action) { assertEquals(status, assertThrows(AdmissionFailure.class, action::run).status()); }

    @Test void exactIssuerSubjectAndSelfRefused() {
        status(400, () -> input("issuer", "E1", "x"));
        assertEquals(new ExternalIdentity("Issuer", "E1"), input("Issuer", "E1", "x").target());
        assertNotEquals(input("issuer", "E2", "x").target(), input("issuer", "E2 ", "x").target());
        status(400, () -> input("issuer", "\uD800", "x"));
    }
    @Test void closedCreationAndDirectLinkRefused() {
        for (String key : List.of("requester", "userId", "expiresAt", "admission", "attestations")) {
            var raw = new HashMap<String, Object>(Map.of("target", Map.of("issuer", "issuer", "subject", "E2"), "bindingToken", "x"));
            raw.put(key, "forged");
            status(400, () -> LinkProposalInput.decode(raw, e1));
        }
        status(400, () -> IdentityAdmissionInput.decode(Map.of("operation", "Link"), e1));
    }
    @Test void getAndLoginDoNotAdmit() {
        var id = create();
        assertEquals("PENDING", service().get(actor(e2), id).state());
        assertNull(admitted); assertNull(association); assertNull(proposal.acceptedAt());
    }
    @Test void onlyExactTargetAcceptsAndMaskingPrecedesState() {
        var id = create(); now = now.plusSeconds(601);
        status(404, () -> service().accept(actor(e1), id));
        status(404, () -> service().accept(actor(new ExternalIdentity("other-issuer", "E2")), id));
        status(404, () -> service().get(actor(new ExternalIdentity("issuer", "E3")), id));
        status(404, () -> service().get(actor(e2), UUID.randomUUID()));
        assertNull(admitted);
    }
    @Test void immutableTwoActorProvenance() {
        var id = create(); var firstExpiry = proposal.principalExpiresAt();
        now = now.plusSeconds(120); // E1 JWT expired; E2 independently authenticated.
        assertTrue(now.isAfter(firstExpiry));
        service().accept(actor(e2), id);
        assertEquals(e1, admitted.requesterEui()); assertEquals(e2, admitted.target());
        assertEquals("SECRET-E1", admitted.bindingToken().value());
        assertEquals("E2", admitted.admission().attestations().get("acceptedSubject"));
        assertEquals(id, association.requestId()); assertEquals(id, proposal.identityRequestId());
    }
    @Test void expiryAndAdmittedReplayOrdering() {
        var id = create(); now = proposal.expiresAt();
        status(410, () -> service().accept(actor(e2), id));
        status(410, () -> service().get(actor(e1), id)); assertNull(admitted);
        now = now.minusSeconds(1); var first = service().accept(actor(e2), id);
        var original = admitted; now = now.plusSeconds(1000);
        assertEquals(first, service().accept(actor(e2), id)); assertSame(original, admitted);
        assertEquals("ACCEPTED", service().get(actor(e1), id).state());
    }
    @Test void canonicalReplayAndConflict() {
        var id = create();
        assertEquals(id, service().create(actor(e1), "key", input("issuer", "E2", "SECRET-E1")).linkRequestId());
        status(409, () -> service().create(actor(e1), "key", input("issuer", "E2", "different")));
        status(409, () -> service().create(actor(e1), "key", input("issuer", "E3", "SECRET-E1")));
        var oldExpiry = proposal.expiresAt(); now = now.plusSeconds(1000);
        assertEquals(oldExpiry, service().create(actor(e1), "key", input("issuer", "E2", "SECRET-E1")).expiresAt());
    }
    @Test void invalidAuthenticationNeverReadsProposal() {
        status(401, () -> service().create(null, "key", input("issuer", "E2", "x")));
        var expired = new AuthenticatedExternalPrincipal("issuer", "E2", now.minusSeconds(3), now.minusSeconds(2), now, Set.of());
        status(401, () -> service().accept(expired, UUID.randomUUID()));
        assertNull(proposal);
    }
    @Test void publicViewsAndLogsExcludeSecrets() {
        var id = create(); var summary = service().get(actor(e2), id);
        assertNull(summary.potCount()); assertNull(summary.potCountAsOf());
        assertFalse(summary.toString().contains("SECRET-E1")); assertFalse(proposal.toString().contains("SECRET-E1"));
        assertEquals(Set.of("linkRequestId", "initiator", "target", "action", "createdAt", "expiresAt", "state", "requestId", "potCount", "potCountAsOf"),
                Arrays.stream(IssueLinkProposal.Summary.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void admissionHasOnlyTechnicalPortsAndHistoricalInputUnchanged() {
        assertEquals(Set.of(AdmitLinkProposal.Store.class, AttemptTransactions.class, AdmissionProtection.class, Clock.class, Duration.class, int.class),
                Set.of(AdmitLinkProposal.class.getConstructors()[0].getParameterTypes()));
        var input = IdentityAdmissionInput.decode(Map.of("operation", "Registration"), e1);
        assertArrayEquals(AdmissionFields.canonical(Map.of("operation", "Registration", "requester", Map.of("issuer", "issuer", "subject", "E1"))), input.canonical(1));
    }
}
