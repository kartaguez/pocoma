package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.InvitationCryptography;

import org.springframework.core.env.Environment;

import java.util.*;

/** External dedicated keyring. Absent configuration never creates an ephemeral production key. */
public final class V3InvitationConfiguration {
    private V3InvitationConfiguration() {}

    public static void requireInstalled(javax.sql.DataSource write, javax.sql.DataSource read) {
        var primary = new org.springframework.jdbc.core.JdbcTemplate(write);
        var projection = new org.springframework.jdbc.core.JdbcTemplate(read);
        if (!Boolean.TRUE.equals(
                        primary.queryForObject(
                                "select to_regclass('v3_invitation') is not null and"
                                    + " to_regclass('v3_invitation_event') is not null and"
                                    + " exists(select 1 from v3_event_counter where"
                                    + " family='INVITATION')",
                                Boolean.class))
                || !Boolean.TRUE.equals(
                        projection.queryForObject(
                                "select to_regclass('read_current_invitation') is not null",
                                Boolean.class)))
            throw new IllegalStateException(
                    "Invitation CURRENT migrations are required before activation");
    }

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
