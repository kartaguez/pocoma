package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;

import java.util.*;

/** Targeted READ projection containing only manifest access sets and shareholders (no expenses loaded). */
public record PotShareholdersView(
        UUID potId,
        long version,
        boolean deleted,
        Set<UUID> authorizedUserIds,
        Set<UUID> attachedActiveUserIds,
        List<PotVersion.Shareholder> shareholders) {

    public PotShareholdersView {
        Objects.requireNonNull(potId, "potId required");
        if (version < 1) {
            throw new IllegalArgumentException("Positive version required");
        }
        authorizedUserIds = canonicalSet(authorizedUserIds);
        attachedActiveUserIds = canonicalSet(attachedActiveUserIds);
        if (!authorizedUserIds.containsAll(attachedActiveUserIds)) {
            throw new IllegalArgumentException("Attached users must be authorized");
        }
        shareholders = List.copyOf(Objects.requireNonNull(shareholders, "shareholders required"));
    }

    private static Set<UUID> canonicalSet(Set<UUID> values) {
        Objects.requireNonNull(values, "user set required");
        if (values.isEmpty()) {
            return Set.of();
        }
        TreeSet<UUID> ordered = new TreeSet<>(SubobjectKey::compareUuid);
        ordered.addAll(values);
        return Collections.unmodifiableSet(ordered);
    }
}
