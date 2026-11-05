package com.kartaguez.pocoma.domain.pot.v3;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** One-way lookup value; a durable attachment intention never retains the bearer secret. */
public final class InvitationTokenHash {
    private InvitationTokenHash() {}

    public static String of(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Token required");
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
