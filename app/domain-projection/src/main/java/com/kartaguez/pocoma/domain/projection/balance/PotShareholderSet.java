package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable canonical binary representation of an ordered set of shareholder UUIDs,
 * addressed by domain-separated SHA-256 hash ({@code pocoma-shset-v1\0}) independent of {@code (pot_id, pot_version)}.
 */
public final class PotShareholderSet {
    private final ContentHash hash;
    private final int schemaVersion;
    private final int shareholdersCount;
    private final List<UUID> shareholderIds;
    private final byte[] payload;

    PotShareholderSet(
            ContentHash hash,
            int schemaVersion,
            int shareholdersCount,
            List<UUID> canonicalShareholderIds,
            byte[] ownedPayload) {
        this.hash = Objects.requireNonNull(hash, "hash required");
        if (schemaVersion != PotShareholderSetBinaryCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotShareholderSet schema version: " + schemaVersion);
        }
        if (shareholdersCount < 0) {
            throw new IllegalArgumentException("Negative shareholdersCount: " + shareholdersCount);
        }
        Objects.requireNonNull(canonicalShareholderIds, "shareholderIds required");
        if (canonicalShareholderIds.size() != shareholdersCount) {
            throw new IllegalArgumentException(
                    "Shareholder IDs count mismatch: " + canonicalShareholderIds.size() + " != " + shareholdersCount);
        }
        UUID previous = null;
        for (UUID id : canonicalShareholderIds) {
            Objects.requireNonNull(id, "shareholderId required");
            if (previous != null && SubobjectKey.compareUuid(previous, id) >= 0) {
                throw new IllegalArgumentException(
                        "PotShareholderSet UUIDs must be strictly ascending: " + previous + " >= " + id);
            }
            previous = id;
        }
        Objects.requireNonNull(ownedPayload, "payload required");
        int expectedLength = expectedPayloadLength(shareholdersCount);
        if (ownedPayload.length != expectedLength) {
            throw new IllegalArgumentException(
                    "PotShareholderSet payload length mismatch: " + ownedPayload.length + " != " + expectedLength);
        }
        this.schemaVersion = schemaVersion;
        this.shareholdersCount = shareholdersCount;
        this.shareholderIds = canonicalShareholderIds;
        this.payload = ownedPayload;
    }

    static int expectedPayloadLength(int shareholdersCount) {
        if (shareholdersCount < 0 || shareholdersCount > (Integer.MAX_VALUE - 4) / 16) {
            throw new IllegalArgumentException("Invalid shareholdersCount: " + shareholdersCount);
        }
        return 4 + shareholdersCount * 16;
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

    public List<UUID> shareholderIds() {
        return shareholderIds;
    }

    public byte[] payload() {
        return payload.clone();
    }

    byte[] rawPayload() {
        return payload;
    }

    public boolean exactContentEquals(PotShareholderSet other) {
        if (this == other) {
            return true;
        }
        return other != null
                && schemaVersion == other.schemaVersion
                && shareholdersCount == other.shareholdersCount
                && Arrays.equals(payload, other.payload);
    }

    public boolean exactContentEquals(
            int otherSchemaVersion, int otherShareholdersCount, byte[] otherPayload) {
        return otherPayload != null
                && schemaVersion == otherSchemaVersion
                && shareholdersCount == otherShareholdersCount
                && Arrays.equals(payload, otherPayload);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj
                || (obj instanceof PotShareholderSet other
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
        return "PotShareholderSet[hash=" + hash
                + ", schemaVersion=" + schemaVersion
                + ", shareholdersCount=" + shareholdersCount
                + ", payloadBytes=" + payload.length + "]";
    }
}
