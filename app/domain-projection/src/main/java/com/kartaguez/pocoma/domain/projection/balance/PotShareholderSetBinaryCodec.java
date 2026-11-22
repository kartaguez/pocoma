package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec.*;

/**
 * Versioned canonical binary codec and domain-separated SHA-256 hashing for {@link PotShareholderSet}.
 *
 * <p>Binary format ({@code schema_version = 1}):
 * <ul>
 *   <li>{@code int32_be(shareholdersCount)}</li>
 *   <li>For each UUID in strict {@link SubobjectKey#compareUuid} order:
 *       {@code int64_be(msb) || int64_be(lsb)}</li>
 * </ul>
 * Hash: {@code SHA-256("pocoma-shset-v1\0" || uint8(1) || payload)}.
 */
public final class PotShareholderSetBinaryCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final byte[] HASH_DOMAIN = "pocoma-shset-v1\0".getBytes(StandardCharsets.US_ASCII);

    private PotShareholderSetBinaryCodec() {}

    public static PotShareholderSet encode(Collection<UUID> shareholderIds) {
        Objects.requireNonNull(shareholderIds, "shareholderIds required");
        var sorted = new TreeSet<UUID>(SubobjectKey::compareUuid);
        for (UUID id : shareholderIds) {
            if (!sorted.add(Objects.requireNonNull(id, "shareholderId required"))) {
                throw new IllegalArgumentException("Duplicate shareholder ID: " + id);
            }
        }
        return encodeValidatedCanonical(List.copyOf(sorted));
    }

    public static PotShareholderSet encodeCanonical(List<UUID> strictlySortedIds) {
        Objects.requireNonNull(strictlySortedIds, "shareholderIds required");
        List<UUID> copy = List.copyOf(strictlySortedIds);
        UUID previous = null;
        for (UUID id : copy) {
            if (previous != null && SubobjectKey.compareUuid(previous, id) >= 0) {
                throw new IllegalArgumentException(
                        "PotShareholderSet UUIDs must be strictly ascending: " + previous + " >= " + id);
            }
            previous = id;
        }
        return encodeValidatedCanonical(copy);
    }

    private static PotShareholderSet encodeValidatedCanonical(List<UUID> canonicalIds) {
        int count = canonicalIds.size();
        int payloadLen = PotShareholderSet.expectedPayloadLength(count);
        var out = new ByteArrayOutputStream(payloadLen);
        writeInt32(out, count);
        for (UUID id : canonicalIds) {
            writeUuid(out, id);
        }
        byte[] payload = out.toByteArray();
        ContentHash hash = computeHash(SCHEMA_VERSION, payload);
        return new PotShareholderSet(hash, SCHEMA_VERSION, count, canonicalIds, payload);
    }

    public static ContentHash computeHash(int schemaVersion, byte[] payload) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotShareholderSet schema version: " + schemaVersion);
        }
        Objects.requireNonNull(payload, "payload required");
        return sha256(HASH_DOMAIN, schemaVersion, payload);
    }

    public static PotShareholderSet decode(
            int schemaVersion,
            int expectedShareholdersCount,
            byte[] payload,
            ContentHash expectedHash) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotShareholderSet schema version: " + schemaVersion);
        }
        int expectedLength = PotShareholderSet.expectedPayloadLength(expectedShareholdersCount);
        Objects.requireNonNull(payload, "payload required");
        if (payload.length != expectedLength) {
            throw new IllegalArgumentException(
                    "PotShareholderSet payload length mismatch: " + payload.length + " != " + expectedLength);
        }
        ContentHash actualHash = computeHash(schemaVersion, payload);
        if (expectedHash != null && !actualHash.equals(expectedHash)) {
            throw new IllegalArgumentException(
                    "PotShareholderSet hash mismatch: expected=" + expectedHash + ", actual=" + actualHash);
        }
        var reader = new Reader(payload);
        int count = reader.readInt32();
        if (count != expectedShareholdersCount) {
            throw new IllegalArgumentException(
                    "PotShareholderSet shareholdersCount mismatch: header=" + count
                            + ", expected=" + expectedShareholdersCount);
        }
        var ids = new ArrayList<UUID>(count);
        UUID previous = null;
        for (int i = 0; i < count; i++) {
            UUID id = reader.readUuid();
            if (previous != null && SubobjectKey.compareUuid(previous, id) >= 0) {
                throw new IllegalArgumentException(
                        "PotShareholderSet UUIDs must be strictly ascending: " + previous + " >= " + id);
            }
            previous = id;
            ids.add(id);
        }
        if (reader.remaining() != 0) {
            throw new IllegalArgumentException(
                    "Trailing bytes in PotShareholderSet payload: " + reader.remaining());
        }
        return new PotShareholderSet(
                expectedHash != null ? expectedHash : actualHash,
                schemaVersion,
                count,
                Collections.unmodifiableList(ids),
                payload.clone());
    }
}
