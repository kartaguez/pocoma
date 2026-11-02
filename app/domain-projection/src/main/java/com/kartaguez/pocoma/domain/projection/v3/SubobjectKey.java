package com.kartaguez.pocoma.domain.projection.v3;

import java.util.Objects;
import java.util.UUID;

/** Local component identity, ordered as unsigned PostgreSQL UUID bytes. */
public record SubobjectKey(UUID potId, SubobjectKind kind, UUID objectId, long localVersion)
        implements Comparable<SubobjectKey> {
    public SubobjectKey {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(objectId);
        if (localVersion < 1) throw new IllegalArgumentException("Positive local version required");
        if ((kind == SubobjectKind.POT_HEADER || kind == SubobjectKind.POT_DETAILS)
                && !objectId.equals(potId)) throw new IllegalArgumentException("Pot object identity required");
    }

    public static int compareUuid(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    @Override public int compareTo(SubobjectKey other) {
        int c = compareUuid(potId, other.potId);
        if (c == 0) c = Integer.compare(kind.rank(), other.kind.rank());
        if (c == 0) c = compareUuid(objectId, other.objectId);
        if (c == 0) c = Long.compare(localVersion, other.localVersion);
        return c;
    }
}
