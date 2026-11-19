package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;

import java.util.*;

/** Targeted READ projection containing manifest access control and only the requested expenses. */
public record PotExpensesView(
        UUID potId,
        long version,
        boolean deleted,
        Set<UUID> authorizedUserIds,
        Map<UUID, PotVersion.Expense> found) {

    public PotExpensesView {
        Objects.requireNonNull(potId, "potId required");
        if (version < 1) {
            throw new IllegalArgumentException("Positive version required");
        }
        authorizedUserIds = canonicalSet(authorizedUserIds);
        found = Map.copyOf(Objects.requireNonNull(found, "found map required"));
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
