package com.kartaguez.pocoma.contracts.command.v3;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned protected framing; the logical lookup key must remain stable across restarts/restores.
 */
public final class AdmissionProtection {
    private final String lookupId, currentId;
    private final byte[] lookup;
    private final Map<String, byte[]> comparisons;

    public AdmissionProtection(
            String lookupId, byte[] lookup, String currentId, Map<String, byte[]> comparisons) {
        this.lookupId = Objects.requireNonNull(lookupId);
        this.currentId = Objects.requireNonNull(currentId);
        if (lookup == null || lookup.length < 32 || lookupId.isBlank() || currentId.isBlank())
            throw new AdmissionFailure(503);
        this.lookup = lookup.clone();
        var copy = new HashMap<String, byte[]>();
        comparisons.forEach(
                (k, v) -> {
                    if (v == null || v.length < 32) throw new AdmissionFailure(503);
                    copy.put(k, v.clone());
                });
        this.comparisons = Map.copyOf(copy);
        key(currentId);
    }

    public String lookupId() {
        return lookupId;
    }

    public String currentId() {
        return currentId;
    }

    public byte[] scope(
            String issuer,
            String subject,
            Optional<String> attestedClient,
            String category,
            String operation,
            String clientKey) {
        AdmissionFailure.require(clientKey != null && clientKey.matches("[A-Za-z0-9._~:-]{1,128}"));
        return hmac(
                lookup,
                "pocoma/v3/admission/scope/1",
                issuer,
                subject,
                attestedClient.isPresent() ? "PRESENT" : "ABSENT",
                attestedClient.orElse(""),
                category,
                operation,
                clientKey);
    }

    public byte[] intent(String keyId, int version, byte[] canonical) {
        if (version != 1) throw new AdmissionFailure(503);
        return hmac(key(keyId), "pocoma/v3/admission/intent/" + version, canonical);
    }

    public boolean matches(AdmissionAssociation association, byte[] canonical) {
        if (!lookupId.equals(association.lookupKeyId())) throw new AdmissionFailure(503);
        return MessageDigest.isEqual(
                association.intentFingerprint(),
                intent(association.comparisonKeyId(), association.canonicalVersion(), canonical));
    }

    private byte[] key(String id) {
        var k = comparisons.get(id);
        if (k == null) throw new AdmissionFailure(503);
        return k;
    }

    public static byte[] frame(String... values) {
        var out = new java.io.ByteArrayOutputStream();
        for (var v : values) {
            byte[] b = AdmissionFields.utf8(Objects.requireNonNull(v));
            out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    private static byte[] hmac(byte[] key, String domain, String... fields) {
        return hmac(key, domain, frame(fields));
    }

    private static byte[] hmac(byte[] key, String domain, byte[] payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(frame(domain));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException failure) {
            throw new AdmissionFailure(503);
        }
    }

    @Override
    public String toString() {
        return "AdmissionProtection[REDACTED]";
    }
}
