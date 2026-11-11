package com.kartaguez.pocoma.infra.security.spring.v3;

import com.kartaguez.pocoma.infra.security.spring.v3.InvitationCryptography;

import org.springframework.core.env.Environment;

import java.util.*;

/** External dedicated keyring. Absent configuration never creates an ephemeral production key. */
public final class V3InvitationConfiguration {
    private V3InvitationConfiguration() {}

    public static InvitationCryptography keys(Environment env) {
        String p = "pocoma.v3.invitation.";
        String current = env.getProperty(p + "current-key");
        if (current == null) return null;
        try {
            var keys = new HashMap<String, byte[]>();
            for (String id : env.getRequiredProperty(p + "key-ids").split(",", -1)) {
                if (keys.putIfAbsent(
                                id,
                                Base64.getDecoder()
                                        .decode(env.getRequiredProperty(p + "keys." + id)))
                        != null) throw new IllegalArgumentException();
            }
            return new InvitationCryptography(current, keys);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Missing or incompatible Invitation keyring");
        }
    }
}
