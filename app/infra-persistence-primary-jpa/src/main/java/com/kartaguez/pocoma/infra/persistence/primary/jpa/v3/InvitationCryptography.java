package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Dedicated externally supplied AES keys. No key/token appears in diagnostics. */
public final class InvitationCryptography {
    private final String current;
    private final Map<String, byte[]> keys;
    private final SecureRandom random = new SecureRandom();

    public InvitationCryptography(String current, Map<String, byte[]> keys) {
        this.current = Objects.requireNonNull(current);
        var copy = new HashMap<String, byte[]>();
        keys.forEach(
                (id, key) -> {
                    if (!id.matches("[A-Za-z0-9_-]+") || key.length != 32)
                        throw new IllegalArgumentException("Invalid invitation key configuration");
                    copy.put(id, key.clone());
                });
        if (!copy.containsKey(current))
            throw new IllegalArgumentException("Missing current invitation key");
        this.keys = Collections.unmodifiableMap(copy);
    }

    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String encrypt(Invitation invitation, String link) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keys.get(current), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(invitation));
            return "v1."
                    + current
                    + "."
                    + encode(nonce)
                    + "."
                    + encode(cipher.doFinal(link.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    public String decrypt(Invitation invitation) {
        try {
            String[] parts = invitation.protectedLink().split("\\.", -1);
            if (parts.length != 4 || !parts[0].equals("v1") || !keys.containsKey(parts[1]))
                throw unavailable();
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != 12) throw unavailable();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keys.get(parts[1]), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(invitation));
            String link =
                    new String(
                            cipher.doFinal(Base64.getUrlDecoder().decode(parts[3])),
                            StandardCharsets.UTF_8);
            String prefix = "pocoma://invitation/";
            if (!link.startsWith(prefix)
                    || !MessageDigest.isEqual(
                            InvitationTokenHash.of(link.substring(prefix.length()))
                                    .getBytes(StandardCharsets.US_ASCII),
                            invitation.tokenHash().getBytes(StandardCharsets.US_ASCII)))
                throw unavailable();
            return link;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Invitation protection unavailable");
    }

    private static byte[] context(Invitation i) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (String value :
                    List.of(
                            "pocoma-invitation-v1",
                            i.invitationId().toString(),
                            Long.toString(i.version()),
                            i.creationRequestId().toString(),
                            i.creatorIssuer(),
                            i.creatorSubject(),
                            i.potId().toString(),
                            i.expiresAt().toString(),
                            i.tokenHash())) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(utf8.length);
                out.write(utf8);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw unavailable();
        }
    }
}
