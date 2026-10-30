package com.kartaguez.pocoma.domain.useridentity.v3;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** One immutable historical state. Absence, including a technical anchor at zero, is not a state. */
public record ExternalUserIdentity(
        ExternalUserIdentityId id,
        ExternalIdentity identity,
        long version,
        PocomaUserId userId,
        BindingToken bindingToken) {
    public ExternalUserIdentity {
        Objects.requireNonNull(id, "id");
        compareKeys(identity, identity);
        if (version < 1) throw new IllegalArgumentException("EUI version must be positive");
        if ((userId == null) != (bindingToken == null))
            throw new IllegalArgumentException("User and token must be present together");
    }

    public boolean isBound() {
        return userId != null;
    }

    /**
     * Canonical issuer-then-subject order, matching PostgreSQL UTF8 COLLATE "C".
     * Validates BOTH complete keys even when the issuers differ. Never normalizes or replaces input.
     * Callers must validate/deduplicate/sort the complete key set before acquiring any EUI lock.
     */
    public static int compareKeys(ExternalIdentity left, ExternalIdentity right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        byte[] leftIssuer = utf8(left.issuer());
        byte[] leftSubject = utf8(left.subject());
        byte[] rightIssuer = utf8(right.issuer());
        byte[] rightSubject = utf8(right.subject());
        int issuerOrder = Arrays.compareUnsigned(leftIssuer, rightIssuer);
        return issuerOrder != 0 ? issuerOrder : Arrays.compareUnsigned(leftSubject, rightSubject);
    }

    private static byte[] utf8(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in external identity");
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] encoded = new byte[bytes.remaining()];
            bytes.get(encoded);
            return encoded;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Malformed external identity", malformed);
        }
    }
}
