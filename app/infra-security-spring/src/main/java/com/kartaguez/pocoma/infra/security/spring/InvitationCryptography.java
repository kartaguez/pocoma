package com.kartaguez.pocoma.infra.security.spring;

import com.kartaguez.pocoma.domain.pot.*;

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

    private record DecodedEnvelope(byte[] key, byte[] nonce, byte[] ciphertext) {}

    private DecodedEnvelope parseProtectedEnvelope(String protectedLink) {
        String[] parts = protectedLink.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v1")) {
            throw unavailable();
        }
        byte[] key = keys.get(parts[1]);
        byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
        if (key == null || nonce.length != 12) {
            throw unavailable();
        }
        return new DecodedEnvelope(key, nonce, Base64.getUrlDecoder().decode(parts[3]));
    }

    public String decrypt(Invitation invitation) {
        try {
            DecodedEnvelope envelope = parseProtectedEnvelope(invitation.protectedLink());
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(envelope.key(), "AES"),
                    new GCMParameterSpec(128, envelope.nonce()));
            cipher.updateAAD(context(invitation));
            String link = new String(cipher.doFinal(envelope.ciphertext()), StandardCharsets.UTF_8);
            verifyDecryptedLink(invitation, link);
            return link;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static void verifyDecryptedLink(Invitation invitation, String link) {
        String prefix = "pocoma://invitation/";
        if (!link.startsWith(prefix)) throw unavailable();
        byte[] actualHash =
                InvitationTokenHash.of(link.substring(prefix.length()))
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] expectedHash = invitation.tokenHash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(actualHash, expectedHash)) throw unavailable();
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
