package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest.*;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

import java.time.Instant;
import java.util.*;

/** Closed provenance codec. Parse failures deliberately retain no payload or Jackson cause. */
public final class IdentityRequestJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private IdentityRequestJson() {}

    public static String operation(IdentityRequest request) {
        return switch (request.operation()) {
            case Registration ignored -> "REGISTRATION";
            case Link ignored -> "LINK";
            case Unlink ignored -> "UNLINK";
        };
    }

    public static Operation operation(String operation, ExternalIdentity target) {
        return switch (operation) {
            case "REGISTRATION" -> new Registration();
            case "LINK" -> new Link(target);
            case "UNLINK" -> new Unlink(target);
            default -> throw invalid();
        };
    }

    public static String encode(AdmissionContext c) {
        var n =
                JSON.createObjectNode()
                        .put("validator", c.validator())
                        .put("contractVersion", c.contractVersion());
        n.set("initiator", key(c.initiator()));
        n.set("target", key(c.target()));
        n.put("intentionFingerprint", c.intentionFingerprint())
                .put("grantOrCorrelationReference", c.grantOrCorrelationReference())
                .put("validatedAt", c.validatedAt().toString())
                .put("origin", c.origin().name());
        var a = n.putObject("attestations");
        new TreeMap<>(c.attestations()).forEach(a::put);
        return n.toString();
    }

    public static AdmissionContext decode(String json) {
        try {
            var n = JSON.readTree(json);
            exact(
                    n,
                    Set.of(
                            "validator",
                            "contractVersion",
                            "initiator",
                            "target",
                            "intentionFingerprint",
                            "grantOrCorrelationReference",
                            "validatedAt",
                            "attestations",
                            "origin"));
            var attestations = new HashMap<String, String>();
            var a = n.required("attestations");
            if (!a.isObject()) throw invalid();
            a.fields()
                    .forEachRemaining(
                            e -> {
                                if (!e.getValue().isTextual()) throw invalid();
                                attestations.put(e.getKey(), e.getValue().textValue());
                            });
            return new AdmissionContext(
                    text(n, "validator"),
                    text(n, "contractVersion"),
                    readKey(n.required("initiator")),
                    readKey(n.required("target")),
                    text(n, "intentionFingerprint"),
                    text(n, "grantOrCorrelationReference"),
                    Instant.parse(text(n, "validatedAt")),
                    attestations,
                    Origin.valueOf(text(n, "origin")));
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static ObjectNode key(ExternalIdentity k) {
        return JSON.createObjectNode().put("issuer", k.issuer()).put("subject", k.subject());
    }

    private static ExternalIdentity readKey(JsonNode n) {
        exact(n, Set.of("issuer", "subject"));
        return new ExternalIdentity(text(n, "issuer"), text(n, "subject"));
    }

    private static String text(JsonNode n, String k) {
        var v = n.required(k);
        if (!v.isTextual()) throw invalid();
        return v.textValue();
    }

    private static void exact(JsonNode n, Set<String> keys) {
        var actual = new HashSet<String>();
        n.fieldNames().forEachRemaining(actual::add);
        if (!n.isObject() || !actual.equals(keys)) throw invalid();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid durable Identity provenance");
    }
}
