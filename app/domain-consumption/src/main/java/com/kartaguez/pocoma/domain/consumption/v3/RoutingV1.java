package com.kartaguez.pocoma.domain.consumption.v3;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public final class RoutingV1 {
    private RoutingV1() {}

    public static int hash(ConsumableFamily family, UUID consumableId) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(consumableId, "consumableId");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("pocoma-consumable-v1".getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(family.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(
                    ByteBuffer.allocate(16)
                            .putLong(consumableId.getMostSignificantBits())
                            .putLong(consumableId.getLeastSignificantBits())
                            .array());
            return ByteBuffer.wrap(digest.digest()).getInt();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM must supply SHA-256", impossible);
        }
    }

    public static int partition(int hash, int count) {
        if (count <= 0) throw new IllegalArgumentException("positive partition count required");
        return Math.floorMod(hash, count);
    }
}
