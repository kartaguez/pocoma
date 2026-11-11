package com.kartaguez.pocoma.contract.writeadmission;

import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.*;

import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.BindingToken;

import java.util.*;

public final class IdentityAdmissionInput {
    private final ExternalIdentity requester, target;
    private final BindingToken token;
    private final String operation;
    private final byte[] canonical;
    private final Object normalized;

    private IdentityAdmissionInput(
            ExternalIdentity requester,
            ExternalIdentity target,
            BindingToken token,
            String operation,
            Object normalized) {
        this.requester = requester;
        this.target = target;
        this.token = token;
        this.operation = operation;
        this.normalized = normalized;
        canonical = AdmissionFields.canonical(normalized);
    }

    public static IdentityAdmissionInput decode(Object raw, ExternalIdentity requester) {
        try {
            if (!(raw instanceof Map<?, ?> m)) throw new AdmissionFailure(400);
            String op = (String) m.get("operation");
            Map<String, Object> n;
            ExternalIdentity target;
            BindingToken token;
            if ("Registration".equals(op)) {
                n = object(raw, "operation");
                target = requester;
                token = null;
            } else if ("Unlink".equals(op)) {
                n = object(raw, "operation", "bindingToken", "target");
                var t = object(n.get("target"), "issuer", "subject");
                target = new ExternalIdentity(nonblank(t, "issuer"), nonblank(t, "subject"));
                token = new BindingToken(nonblank(n, "bindingToken"));
                n.put("target", t);
            } else throw new AdmissionFailure(400);
            n.put(
                    "requester",
                    Map.of("issuer", requester.issuer(), "subject", requester.subject()));
            return new IdentityAdmissionInput(requester, target, token, op, n);
        } catch (AdmissionFailure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new AdmissionFailure(400);
        }
    }

    public String operation() {
        return operation;
    }

    public ExternalIdentity requester() {
        return requester;
    }

    public ExternalIdentity target() {
        return target;
    }

    public BindingToken token() {
        return token;
    }

    public IdentityRequest.Operation intention() {
        return operation.equals("Registration")
                ? new IdentityRequest.Registration()
                : new IdentityRequest.Unlink(target);
    }

    public byte[] canonical(int version) {
        if (version != 1) throw new AdmissionFailure(503);
        return canonical.clone();
    }

    public void validateCurrent(int textBytes) {
        textsWithin(normalized, textBytes);
    }

    @Override
    public String toString() {
        return "IdentityAdmissionInput[REDACTED]";
    }
}
