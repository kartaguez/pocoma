package com.kartaguez.pocoma.domain.projection.radix;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical, non-empty, deduplicated, unsigned-UUID-sorted user set addressed by SHA-256.
 * Empty sets are represented by {@code null} hashes on {@code read_pot_version}.
 */
public final class PotUserSet {
    public static final int SCHEMA_VERSION = 1;
    private static final byte[] HASH_DOMAIN = "pocoma-uset-v1\0".getBytes(StandardCharsets.US_ASCII);

    private final ContentHash hash;
    private final int schemaVersion;
    private final List<UUID> userIds;

    private PotUserSet(ContentHash hash, int schemaVersion, List<UUID> userIds) {
        this.hash = Objects.requireNonNull(hash, "hash required");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotUserSet schemaVersion: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("PotUserSet must be non-empty");
        }
        for (int i = 0; i < userIds.size(); i++) {
            UUID current = Objects.requireNonNull(userIds.get(i), "userId required");
            if (i > 0 && SubobjectKey.compareUuid(userIds.get(i - 1), current) >= 0) {
                throw new IllegalArgumentException("PotUserSet userIds must be strictly sorted and deduplicated");
            }
        }
        this.userIds = List.copyOf(userIds);
    }

    public static Optional<PotUserSet> of(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds required");
        if (userIds.isEmpty()) {
            return Optional.empty();
        }
        TreeSet<UUID> sorted = new TreeSet<>(SubobjectKey::compareUuid);
        for (UUID id : userIds) {
            sorted.add(Objects.requireNonNull(id, "userId required"));
        }
        List<UUID> canonical = List.copyOf(sorted);
        ContentHash hash = computeHash(SCHEMA_VERSION, canonical);
        return Optional.of(new PotUserSet(hash, SCHEMA_VERSION, canonical));
    }

    public static PotUserSet fromStored(ContentHash hash, int schemaVersion, List<UUID> userIds) {
        return new PotUserSet(hash, schemaVersion, userIds);
    }

    public static ContentHash computeHash(int schemaVersion, List<UUID> canonicalUserIds) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotUserSet schemaVersion: " + schemaVersion);
        }
        if (canonicalUserIds == null || canonicalUserIds.isEmpty()) {
            throw new IllegalArgumentException("PotUserSet must be non-empty");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(HASH_DOMAIN);
            md.update((byte) schemaVersion);
            ByteBuffer buf = ByteBuffer.allocate(4 + canonicalUserIds.size() * 16);
            buf.putInt(canonicalUserIds.size());
            for (UUID id : canonicalUserIds) {
                buf.putLong(id.getMostSignificantBits());
                buf.putLong(id.getLeastSignificantBits());
            }
            md.update(buf.array());
            return ContentHash.wrapOwned(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    public ContentHash hash() {
        return hash;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int userCount() {
        return userIds.size();
    }

    public List<UUID> userIds() {
        return userIds;
    }

    public Set<UUID> toCanonicalSet() {
        TreeSet<UUID> set = new TreeSet<>(SubobjectKey::compareUuid);
        set.addAll(userIds);
        return Collections.unmodifiableSet(set);
    }

    public boolean verifyHash() {
        return hash.equals(computeHash(schemaVersion, userIds));
    }

    public boolean exactContentEquals(PotUserSet other) {
        if (other == null) {
            return false;
        }
        return schemaVersion == other.schemaVersion
                && hash.equals(other.hash)
                && userIds.equals(other.userIds);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof PotUserSet other && exactContentEquals(other));
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, schemaVersion, userIds);
    }
}
