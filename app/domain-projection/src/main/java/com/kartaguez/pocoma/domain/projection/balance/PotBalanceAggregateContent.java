package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable canonical binary representation of a {@link com.kartaguez.pocoma.domain.projection.PotBalanceAggregate}
 * content, referencing a shared {@link PotShareholderSet} and addressed by domain-separated SHA-256 hash
 * independent of {@code (pot_id, pot_version)}.
 */
public final class PotBalanceAggregateContent {
    private final ContentHash hash;
    private final int schemaVersion;
    private final int shareholdersCount;
    private final int bucketCount;
    private final ContentHash shareholderSetHash;
    private final byte[] payload;

    PotBalanceAggregateContent(
            ContentHash hash,
            int schemaVersion,
            int shareholdersCount,
            int bucketCount,
            ContentHash shareholderSetHash,
            byte[] ownedPayload) {
        this.hash = Objects.requireNonNull(hash, "hash required");
        if (schemaVersion != PotBalanceAggregateBinaryCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalanceAggregate schema version: " + schemaVersion);
        }
        if (shareholdersCount < 0) {
            throw new IllegalArgumentException("Negative shareholdersCount: " + shareholdersCount);
        }
        if (bucketCount < 0) {
            throw new IllegalArgumentException("Negative bucketCount: " + bucketCount);
        }
        this.shareholderSetHash = Objects.requireNonNull(shareholderSetHash, "shareholderSetHash required");
        Objects.requireNonNull(ownedPayload, "payload required");
        if (ownedPayload.length < 8) {
            throw new IllegalArgumentException("PotBalanceAggregate payload too short: " + ownedPayload.length);
        }
        this.schemaVersion = schemaVersion;
        this.shareholdersCount = shareholdersCount;
        this.bucketCount = bucketCount;
        this.payload = ownedPayload;
    }

    public static PotBalanceAggregateContent of(
            ContentHash hash,
            int schemaVersion,
            int shareholdersCount,
            int bucketCount,
            ContentHash shareholderSetHash,
            byte[] payload) {
        Objects.requireNonNull(payload, "payload required");
        return new PotBalanceAggregateContent(
                hash, schemaVersion, shareholdersCount, bucketCount, shareholderSetHash, payload.clone());
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

    public int bucketCount() {
        return bucketCount;
    }

    public ContentHash shareholderSetHash() {
        return shareholderSetHash;
    }

    public byte[] payload() {
        return payload.clone();
    }

    byte[] rawPayload() {
        return payload;
    }

    public boolean exactContentEquals(PotBalanceAggregateContent other) {
        if (this == other) {
            return true;
        }
        return other != null
                && schemaVersion == other.schemaVersion
                && shareholdersCount == other.shareholdersCount
                && bucketCount == other.bucketCount
                && shareholderSetHash.equals(other.shareholderSetHash)
                && Arrays.equals(payload, other.payload);
    }

    public boolean exactContentEquals(
            int otherSchemaVersion,
            int otherShareholdersCount,
            int otherBucketCount,
            ContentHash otherShareholderSetHash,
            byte[] otherPayload) {
        return otherPayload != null
                && otherShareholderSetHash != null
                && schemaVersion == otherSchemaVersion
                && shareholdersCount == otherShareholdersCount
                && bucketCount == otherBucketCount
                && shareholderSetHash.equals(otherShareholderSetHash)
                && Arrays.equals(payload, otherPayload);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj
                || (obj instanceof PotBalanceAggregateContent other
                        && hash.equals(other.hash)
                        && exactContentEquals(other));
    }

    @Override
    public int hashCode() {
        int result = hash.hashCode();
        result = 31 * result + schemaVersion;
        result = 31 * result + shareholdersCount;
        result = 31 * result + bucketCount;
        result = 31 * result + shareholderSetHash.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "PotBalanceAggregateContent[hash=" + hash
                + ", schemaVersion=" + schemaVersion
                + ", shareholdersCount=" + shareholdersCount
                + ", bucketCount=" + bucketCount
                + ", shareholderSetHash=" + shareholderSetHash
                + ", payloadBytes=" + payload.length + "]";
    }
}
