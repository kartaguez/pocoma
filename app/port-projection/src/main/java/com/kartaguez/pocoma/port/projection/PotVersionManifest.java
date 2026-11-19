package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Codec;

import java.util.*;

/** Immutable logical manifest row for one (pot_id, pot_version) in Radix-16 storage. */
public record PotVersionManifest(
        UUID potId,
        long version,
        int schemaVersion,
        long headerVersion,
        long detailsVersion,
        boolean deleted,
        int shareholdersCount,
        int expensesCount,
        ContentHash shareholdersRoot,
        ContentHash expensesRoot,
        ContentHash authorizedUsersHash,
        ContentHash attachedActiveUsersHash,
        Set<UUID> authorizedUserIds,
        Set<UUID> attachedActiveUserIds) {

    public PotVersionManifest {
        Objects.requireNonNull(potId, "potId required");
        if (version < 1 || headerVersion < 1 || detailsVersion < 1) {
            throw new IllegalArgumentException("Positive versions required");
        }
        if (schemaVersion != Radix16Codec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported manifest schemaVersion: " + schemaVersion);
        }
        if (shareholdersCount < 0 || expensesCount < 0) {
            throw new IllegalArgumentException("Non-negative counts required");
        }
        if ((shareholdersCount == 0) != (shareholdersRoot == null)) {
            throw new IllegalArgumentException("shareholdersCount/shareholdersRoot nullability mismatch");
        }
        if ((expensesCount == 0) != (expensesRoot == null)) {
            throw new IllegalArgumentException("expensesCount/expensesRoot nullability mismatch");
        }
        authorizedUserIds = canonicalSet(authorizedUserIds);
        attachedActiveUserIds = canonicalSet(attachedActiveUserIds);
        if ((authorizedUsersHash == null) != authorizedUserIds.isEmpty()) {
            throw new IllegalArgumentException("authorizedUsersHash/authorizedUserIds mismatch");
        }
        if ((attachedActiveUsersHash == null) != attachedActiveUserIds.isEmpty()) {
            throw new IllegalArgumentException("attachedActiveUsersHash/attachedActiveUserIds mismatch");
        }
        if (!authorizedUserIds.containsAll(attachedActiveUserIds)) {
            throw new IllegalArgumentException("Attached users must be authorized");
        }
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
