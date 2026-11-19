package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable canonical binary representation of a {@link com.kartaguez.pocoma.domain.projection.PotBalance}
 * content, addressed by domain-separated SHA-256 hash independent of {@code (pot_id, pot_version)}.
 */
public final class PotBalanceContent {
    private final ContentHash hash;
    private final int schemaVersion;
    private final int shareholdersCount;
    private final byte[] payload;

    PotBalanceContent(ContentHash hash, int schemaVersion, int shareholdersCount, byte[] ownedPayload) {
        this.hash = Objects.requireNonNull(hash, "hash required");
        if (schemaVersion != PotBalanceBinaryCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalance schema version: " + schemaVersion);
        }
        if (shareholdersCount < 0) {
            throw new IllegalArgumentException("Negative shareholdersCount: " + shareholdersCount);
        }
        Objects.requireNonNull(ownedPayload, "payload required");
        if (ownedPayload.length < 4) {
            throw new IllegalArgumentException("PotBalance payload too short: " + ownedPayload.length);
        }
        this.schemaVersion = schemaVersion;
        this.shareholdersCount = shareholdersCount;
        this.payload = ownedPayload;
    }

    public static PotBalanceContent of(
            ContentHash hash, int schemaVersion, int shareholdersCount, byte[] payload) {
        Objects.requireNonNull(payload, "payload required");
        return new PotBalanceContent(hash, schemaVersion, shareholdersCount, payload.clone());
    }

    public ContentHash hash() {
        return hash;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int shareholdersCount() {
        return shareholdersCount;
    }

    public byte[] payload() {
        return payload.clone();
    }

    byte[] rawPayload() {
        return payload;
    }

    public boolean exactContentEquals(PotBalanceContent other) {
        if (this == other) {
            return true;
        }
        return other != null
                && schemaVersion == other.schemaVersion
                && shareholdersCount == other.shareholdersCount
                && Arrays.equals(payload, other.payload);
    }

    public boolean exactContentEquals(int otherSchemaVersion, int otherShareholdersCount, byte[] otherPayload) {
        return otherPayload != null
                && schemaVersion == otherSchemaVersion
                && shareholdersCount == otherShareholdersCount
                && Arrays.equals(payload, otherPayload);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj
                || (obj instanceof PotBalanceContent other
                        && hash.equals(other.hash)
                        && exactContentEquals(other));
    }

    @Override
    public int hashCode() {
        int result = hash.hashCode();
        result = 31 * result + schemaVersion;
        result = 31 * result + shareholdersCount;
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "PotBalanceContent[hash=" + hash
                + ", schemaVersion=" + schemaVersion
                + ", shareholdersCount=" + shareholdersCount
                + ", payloadBytes=" + payload.length + "]";
    }
}
