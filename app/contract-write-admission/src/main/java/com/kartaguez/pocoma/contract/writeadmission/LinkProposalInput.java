package com.kartaguez.pocoma.contract.writeadmission;

import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.*;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.BindingToken;
import java.util.*;

/** Closed, provider-neutral intention; no authority check at admission. */
public final class LinkProposalInput {
    private final ExternalIdentity initiator, target;
    private final BindingToken token;
    private final Map<String, Object> normalized;
    private LinkProposalInput(ExternalIdentity initiator, ExternalIdentity target, BindingToken token) {
        this.initiator = initiator; this.target = target; this.token = token;
        AdmissionFailure.require(!initiator.equals(target));
        normalized = Map.of("contract", "link-proposal/1", "initiator", eui(initiator),
                "target", eui(target), "bindingToken", token.value());
        canonical(1); // Reject malformed UTF-16 before any durable access.
    }
    private static Map<String, String> eui(ExternalIdentity e) {
        return Map.of("issuer", e.issuer(), "subject", e.subject());
    }
    public static LinkProposalInput decode(Object raw, ExternalIdentity initiator) {
        var m = object(raw, "bindingToken", "target");
        var t = object(m.get("target"), "issuer", "subject");
        return new LinkProposalInput(initiator,
                new ExternalIdentity(nonblank(t, "issuer"), nonblank(t, "subject")),
                new BindingToken(nonblank(m, "bindingToken")));
    }
    public ExternalIdentity initiator() { return initiator; }
    public ExternalIdentity target() { return target; }
    public BindingToken token() { return token; }
    public byte[] canonical(int version) {
        if (version != 1) throw new AdmissionFailure(503);
        return AdmissionFields.canonical(normalized);
    }
    public void validateCurrent(int textBytes) { textsWithin(normalized, textBytes); }
    @Override public String toString() { return "LinkProposalInput[REDACTED]"; }
}
