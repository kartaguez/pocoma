package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec.*;

/**
 * Versioned canonical binary codec and domain-separated SHA-256 hashing for {@link PotBalanceAggregate}.
 *
 * <p>Binary format ({@code schema_version = 1}, referencing {@link PotShareholderSet} for UUIDs):
 * <ul>
 *   <li>{@code int32_be(shareholdersCount)}</li>
 *   <li>For each {@link ShareholderEntry} in strict {@link SubobjectKey#compareUuid} order:
 *       {@code uint8(deleted ? 1 : 0) || encodeFraction(defaultWeight) || encodeFraction(fixedNet)}</li>
 *   <li>{@code int32_be(bucketCount)}</li>
 *   <li>For each {@code (BucketKey, BucketValue)} in strict {@link BucketKey#compareTo} order:
 *       {@code int32_be(defaultIndices.size()) || int32_be(idx_0)... || encodeFraction(specificWeightTotal)
 *              || encodeFraction(totalAmount) || int32_be(specWeightedAmounts.size())
 *              || (int32_be(idx) || encodeFraction(frac))...}</li>
 * </ul>
 * Hash: {@code SHA-256("pocoma-bagg-v1\0" || uint8(1) || shareholderSetHash(32B) || payload)}.
 */
public final class PotBalanceAggregateBinaryCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final byte[] HASH_DOMAIN = "pocoma-bagg-v1\0".getBytes(StandardCharsets.US_ASCII);

    public record EncodedAggregate(PotShareholderSet shareholderSet, PotBalanceAggregateContent content) {
        public EncodedAggregate {
            Objects.requireNonNull(shareholderSet, "shareholderSet required");
            Objects.requireNonNull(content, "content required");
            if (!shareholderSet.hash().equals(content.shareholderSetHash())) {
                throw new IllegalArgumentException("Shareholder set hash mismatch");
            }
        }
    }

    private PotBalanceAggregateBinaryCodec() {}

    public static EncodedAggregate encode(PotBalanceAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate required");
        return encodeInternal(aggregate, null);
    }

    public static EncodedAggregate encodeWithShareholderSet(
            PotBalanceAggregate aggregate, PotShareholderSet knownSet) {
        Objects.requireNonNull(aggregate, "aggregate required");
        Objects.requireNonNull(knownSet, "knownSet required");
        return encodeInternal(aggregate, knownSet);
    }

    private static EncodedAggregate encodeInternal(
            PotBalanceAggregate aggregate, PotShareholderSet knownSet) {
        List<ShareholderEntry> shareholders = aggregate.shareholders();
        Map<BucketKey, BucketValue> buckets = aggregate.buckets();
        int shareholdersCount = shareholders.size();
        int bucketCount = buckets.size();

        PotShareholderSet shareholderSet = resolveShareholderSet(shareholders, knownSet);

        var out = new ByteArrayOutputStream(8 + shareholdersCount * 16 + bucketCount * 64);
        writeInt32(out, shareholdersCount);
        for (ShareholderEntry entry : shareholders) {
            out.write(entry.deleted() ? 1 : 0);
            encodeFraction(out, entry.defaultWeight());
            encodeFraction(out, entry.fixedNet());
        }
        writeInt32(out, bucketCount);
        for (var bucket : buckets.entrySet()) {
            BucketKey key = bucket.getKey();
            BucketValue value = bucket.getValue();
            List<Integer> defaultIndices = key.defaultParticipantIndices();
            writeInt32(out, defaultIndices.size());
            for (int idx : defaultIndices) {
                writeInt32(out, idx);
            }
            encodeFraction(out, key.specificWeightTotal());
            encodeFraction(out, value.totalAmount());
            Map<Integer, Fraction> specAmounts = value.specWeightedAmounts();
            writeInt32(out, specAmounts.size());
            for (var spec : specAmounts.entrySet()) {
                writeInt32(out, spec.getKey());
                encodeFraction(out, spec.getValue());
            }
        }
        byte[] payload = out.toByteArray();
        ContentHash hash = computeHash(SCHEMA_VERSION, shareholderSet.hash(), payload);
        return new EncodedAggregate(
                shareholderSet,
                new PotBalanceAggregateContent(
                        hash, SCHEMA_VERSION, shareholdersCount, bucketCount, shareholderSet.hash(), payload));
    }

    private static PotShareholderSet resolveShareholderSet(
            List<ShareholderEntry> shareholders, PotShareholderSet knownSet) {
        int count = shareholders.size();
        if (knownSet != null && knownSet.shareholdersCount() == count) {
            List<UUID> knownIds = knownSet.shareholderIds();
            boolean same = true;
            for (int i = 0; i < count; i++) {
                if (!shareholders.get(i).shareholderId().equals(knownIds.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return knownSet;
            }
        }
        List<UUID> ids = new ArrayList<>(count);
        for (ShareholderEntry entry : shareholders) {
            ids.add(entry.shareholderId());
        }
        return PotShareholderSetBinaryCodec.encodeCanonical(ids);
    }

    public static ContentHash computeHash(
            int schemaVersion, ContentHash shareholderSetHash, byte[] payload) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalanceAggregate schema version: " + schemaVersion);
        }
        Objects.requireNonNull(shareholderSetHash, "shareholderSetHash required");
        Objects.requireNonNull(payload, "payload required");
        return sha256(HASH_DOMAIN, schemaVersion, shareholderSetHash.bytes(), payload);
    }

    public static PotBalanceAggregate decode(
            UUID potId,
            long version,
            PotShareholderSet shareholderSet,
            PotBalanceAggregateContent content) {
        Objects.requireNonNull(shareholderSet, "shareholderSet required");
        Objects.requireNonNull(content, "content required");
        if (!shareholderSet.hash().equals(content.shareholderSetHash())) {
            throw new IllegalArgumentException(
                    "PotBalanceAggregate shareholderSetHash mismatch: set=" + shareholderSet.hash()
                            + ", content=" + content.shareholderSetHash());
        }
        return decode(
                potId,
                version,
                shareholderSet,
                content.schemaVersion(),
                content.shareholdersCount(),
                content.bucketCount(),
                content.rawPayload(),
                content.hash());
    }

    public static PotBalanceAggregate decode(
            UUID potId,
            long version,
            PotShareholderSet shareholderSet,
            int schemaVersion,
            int expectedShareholdersCount,
            int expectedBucketCount,
            byte[] payload,
            ContentHash expectedHash) {
        Objects.requireNonNull(potId, "potId required");
        Objects.requireNonNull(shareholderSet, "shareholderSet required");
        if (version < 1) {
            throw new IllegalArgumentException("Positive version required");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalanceAggregate schema version: " + schemaVersion);
        }
        if (expectedShareholdersCount < 0) {
            throw new IllegalArgumentException("Negative shareholdersCount: " + expectedShareholdersCount);
        }
        if (expectedBucketCount < 0) {
            throw new IllegalArgumentException("Negative bucketCount: " + expectedBucketCount);
        }
        if (shareholderSet.shareholdersCount() != expectedShareholdersCount) {
            throw new IllegalArgumentException(
                    "PotBalanceAggregate shareholder set count mismatch: set="
                            + shareholderSet.shareholdersCount()
                            + ", expected=" + expectedShareholdersCount);
        }
        Objects.requireNonNull(payload, "payload required");
        if (payload.length < 8) {
            throw new IllegalArgumentException("PotBalanceAggregate payload too short: " + payload.length);
        }
        if (expectedHash != null) {
            ContentHash actualHash = computeHash(schemaVersion, shareholderSet.hash(), payload);
            if (!actualHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                        "PotBalanceAggregate content hash mismatch: expected=" + expectedHash + ", actual=" + actualHash);
            }
        }

        var reader = new Reader(payload);
        int shareholdersCount = reader.readInt32();
        if (shareholdersCount != expectedShareholdersCount) {
            throw new IllegalArgumentException(
                    "PotBalanceAggregate shareholdersCount mismatch: header=" + shareholdersCount
                            + ", expected=" + expectedShareholdersCount);
        }
        List<UUID> shareholderIds = shareholderSet.shareholderIds();
        var shareholders = new ArrayList<ShareholderEntry>(shareholdersCount);
        for (int i = 0; i < shareholdersCount; i++) {
            UUID id = shareholderIds.get(i);
            int deletedFlag = reader.readUint8();
            if (deletedFlag != 0 && deletedFlag != 1) {
                throw new IllegalArgumentException("Invalid deleted flag byte: " + deletedFlag);
            }
            Fraction defaultWeight = reader.readFraction();
            Fraction fixedNet = reader.readFraction();
            shareholders.add(new ShareholderEntry(id, defaultWeight, deletedFlag == 1, fixedNet));
        }

        int bucketCount = reader.readInt32();
        if (bucketCount != expectedBucketCount) {
            throw new IllegalArgumentException(
                    "PotBalanceAggregate bucketCount mismatch: header=" + bucketCount
                            + ", expected=" + expectedBucketCount);
        }
        var buckets = new LinkedHashMap<BucketKey, BucketValue>(Math.max(16, bucketCount * 2));
        BucketKey previousKey = null;
        for (int i = 0; i < bucketCount; i++) {
            int defaultCount = reader.readInt32();
            if (defaultCount < 1 || defaultCount > shareholdersCount) {
                throw new IllegalArgumentException("Invalid default participant count: " + defaultCount);
            }
            var defaultIndices = new ArrayList<Integer>(defaultCount);
            int prevIdx = -1;
            for (int j = 0; j < defaultCount; j++) {
                int idx = reader.readInt32();
                if (idx <= prevIdx || idx >= shareholdersCount) {
                    throw new IllegalArgumentException(
                            "Default participant index out of order or bounds: " + idx);
                }
                prevIdx = idx;
                defaultIndices.add(idx);
            }
            Fraction specificWeightTotal = reader.readFraction();
            Fraction totalAmount = reader.readFraction();
            int specCount = reader.readInt32();
            if (specCount < 0 || specCount > shareholdersCount) {
                throw new IllegalArgumentException("Invalid specific participant count: " + specCount);
            }
            var specAmounts = new LinkedHashMap<Integer, Fraction>(Math.max(8, specCount * 2));
            int prevSpecIdx = -1;
            for (int j = 0; j < specCount; j++) {
                int idx = reader.readInt32();
                if (idx <= prevSpecIdx || idx >= shareholdersCount) {
                    throw new IllegalArgumentException(
                            "Specific participant index out of order or bounds: " + idx);
                }
                prevSpecIdx = idx;
                Fraction amount = reader.readFraction();
                specAmounts.put(idx, amount);
            }
            BucketKey key = new BucketKey(defaultIndices, specificWeightTotal);
            if (previousKey != null && previousKey.compareTo(key) >= 0) {
                throw new IllegalArgumentException(
                        "PotBalanceAggregate buckets must be strictly ascending by BucketKey");
            }
            previousKey = key;
            buckets.put(key, new BucketValue(totalAmount, specAmounts));
        }

        if (reader.remaining() != 0) {
            throw new IllegalArgumentException(
                    "Trailing bytes in PotBalanceAggregate payload: " + reader.remaining());
        }
        return new PotBalanceAggregate(potId, version, shareholders, buckets);
    }
}
