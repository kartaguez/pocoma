package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Versioned canonical binary codec and domain-separated SHA-256 hashing for {@link PotBalance}.
 *
 * <p>Binary format ({@code schema_version = 1}, referencing {@link PotShareholderSet} for UUIDs):
 * <ul>
 *   <li>{@code int32_be(shareholdersCount)}</li>
 *   <li>For each entry in strict {@link SubobjectKey#compareUuid} order (matching {@link PotShareholderSet}):
 *       {@code encodeFraction(value)}</li>
 * </ul>
 * Hash: {@code SHA-256("pocoma-bal-v1\0" || uint8(1) || shareholderSetHash(32B) || payload)}.
 */
public final class PotBalanceBinaryCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final byte[] HASH_DOMAIN = "pocoma-bal-v1\0".getBytes(StandardCharsets.US_ASCII);

    public record EncodedBalance(PotShareholderSet shareholderSet, PotBalanceContent content) {
        public EncodedBalance {
            Objects.requireNonNull(shareholderSet, "shareholderSet required");
            Objects.requireNonNull(content, "content required");
            if (!shareholderSet.hash().equals(content.shareholderSetHash())) {
                throw new IllegalArgumentException("Shareholder set hash mismatch");
            }
        }
    }

    private PotBalanceBinaryCodec() {}

    public static EncodedBalance encode(PotBalance balance) {
        Objects.requireNonNull(balance, "balance required");
        return encodeCanonicalValues(balance.values(), null);
    }

    public static EncodedBalance encodeWithShareholderSet(
            PotBalance balance, PotShareholderSet knownSet) {
        Objects.requireNonNull(balance, "balance required");
        Objects.requireNonNull(knownSet, "knownSet required");
        return encodeCanonicalValues(balance.values(), knownSet);
    }

    public static EncodedBalance encode(Map<UUID, Fraction> values) {
        Objects.requireNonNull(values, "values required");
        var sorted = new TreeMap<UUID, Fraction>(SubobjectKey::compareUuid);
        for (var entry : values.entrySet()) {
            UUID id = Objects.requireNonNull(entry.getKey(), "shareholder id required");
            Fraction value = Objects.requireNonNull(entry.getValue(), "balance fraction required");
            sorted.put(id, value);
        }
        Fraction sum = Fraction.ZERO;
        for (Fraction f : sorted.values()) {
            sum = sum.add(f);
        }
        if (!sum.equals(Fraction.ZERO)) {
            throw new IllegalArgumentException("Unbalanced vector: sum=" + sum);
        }
        return encodeCanonicalValues(sorted, null);
    }

    private static EncodedBalance encodeCanonicalValues(
            Map<UUID, Fraction> sortedValues, PotShareholderSet knownSet) {
        int count = sortedValues.size();
        PotShareholderSet shareholderSet = resolveShareholderSet(sortedValues, knownSet);
        var out = new ByteArrayOutputStream(4 + count * 6);
        writeInt32(out, count);
        for (Fraction value : sortedValues.values()) {
            encodeFraction(out, value);
        }
        byte[] payload = out.toByteArray();
        ContentHash hash = computeHash(SCHEMA_VERSION, shareholderSet.hash(), payload);
        return new EncodedBalance(
                shareholderSet,
                new PotBalanceContent(hash, SCHEMA_VERSION, count, shareholderSet.hash(), payload));
    }

    private static PotShareholderSet resolveShareholderSet(
            Map<UUID, Fraction> sortedValues, PotShareholderSet knownSet) {
        if (knownSet != null && matchesOrderedKeys(sortedValues.keySet(), knownSet.shareholderIds())) {
            return knownSet;
        }
        return PotShareholderSetBinaryCodec.encodeCanonical(List.copyOf(sortedValues.keySet()));
    }

    private static boolean matchesOrderedKeys(Set<UUID> keys, List<UUID> canonicalIds) {
        if (keys.size() != canonicalIds.size()) {
            return false;
        }
        int i = 0;
        for (UUID key : keys) {
            if (!key.equals(canonicalIds.get(i++))) {
                return false;
            }
        }
        return true;
    }

    public static ContentHash computeHash(
            int schemaVersion, ContentHash shareholderSetHash, byte[] payload) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalance schema version: " + schemaVersion);
        }
        Objects.requireNonNull(shareholderSetHash, "shareholderSetHash required");
        Objects.requireNonNull(payload, "payload required");
        return sha256(HASH_DOMAIN, schemaVersion, shareholderSetHash.bytes(), payload);
    }

    public static PotBalance decode(
            UUID potId, long version, PotShareholderSet shareholderSet, PotBalanceContent content) {
        Objects.requireNonNull(shareholderSet, "shareholderSet required");
        Objects.requireNonNull(content, "content required");
        if (!shareholderSet.hash().equals(content.shareholderSetHash())) {
            throw new IllegalArgumentException(
                    "PotBalance shareholderSetHash mismatch: set=" + shareholderSet.hash()
                            + ", content=" + content.shareholderSetHash());
        }
        return decode(
                potId,
                version,
                shareholderSet,
                content.schemaVersion(),
                content.shareholdersCount(),
                content.rawPayload(),
                content.hash());
    }

    public static PotBalance decode(
            UUID potId,
            long version,
            PotShareholderSet shareholderSet,
            int schemaVersion,
            int expectedShareholdersCount,
            byte[] payload,
            ContentHash expectedHash) {
        Objects.requireNonNull(potId, "potId required");
        Objects.requireNonNull(shareholderSet, "shareholderSet required");
        if (version < 1) {
            throw new IllegalArgumentException("Positive version required");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported PotBalance schema version: " + schemaVersion);
        }
        if (expectedShareholdersCount < 0) {
            throw new IllegalArgumentException("Negative shareholdersCount: " + expectedShareholdersCount);
        }
        if (shareholderSet.shareholdersCount() != expectedShareholdersCount) {
            throw new IllegalArgumentException(
                    "PotBalance shareholder set count mismatch: set=" + shareholderSet.shareholdersCount()
                            + ", expected=" + expectedShareholdersCount);
        }
        Objects.requireNonNull(payload, "payload required");
        if (payload.length < 4) {
            throw new IllegalArgumentException("PotBalance payload too short: " + payload.length);
        }
        if (expectedHash != null) {
            ContentHash actualHash = computeHash(schemaVersion, shareholderSet.hash(), payload);
            if (!actualHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                        "PotBalance content hash mismatch: expected=" + expectedHash + ", actual=" + actualHash);
            }
        }

        var reader = new Reader(payload);
        int count = reader.readInt32();
        if (count != expectedShareholdersCount) {
            throw new IllegalArgumentException(
                    "PotBalance shareholdersCount mismatch: header=" + count
                            + ", expected=" + expectedShareholdersCount);
        }
        List<UUID> shareholderIds = shareholderSet.shareholderIds();
        var values = new LinkedHashMap<UUID, Fraction>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            UUID id = shareholderIds.get(i);
            Fraction fraction = reader.readFraction();
            values.put(id, fraction);
        }
        if (reader.remaining() != 0) {
            throw new IllegalArgumentException(
                    "Trailing bytes in PotBalance payload: " + reader.remaining());
        }
        return new PotBalance(potId, version, values);
    }

    // -------------------------------------------------------------------------
    // Shared package-private canonical binary I/O helpers (used by all balance codecs)
    // -------------------------------------------------------------------------

    static ContentHash sha256(byte[] domain, int schemaVersion, byte[] payload) {
        return sha256(domain, schemaVersion, null, payload);
    }

    static ContentHash sha256(
            byte[] domain, int schemaVersion, byte[] referencedSetHash, byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(domain);
            md.update((byte) schemaVersion);
            if (referencedSetHash != null) {
                md.update(referencedSetHash);
            }
            md.update(payload);
            return ContentHash.of(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    static void writeInt64(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 56) & 0xFF));
        out.write((int) ((value >>> 48) & 0xFF));
        out.write((int) ((value >>> 40) & 0xFF));
        out.write((int) ((value >>> 32) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    static void writeUuid(ByteArrayOutputStream out, UUID id) {
        writeInt64(out, id.getMostSignificantBits());
        writeInt64(out, id.getLeastSignificantBits());
    }

    static void writeVarInt32(ByteArrayOutputStream out, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative varint32 value: " + value);
        }
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static void encodeFraction(ByteArrayOutputStream out, Fraction value) {
        byte[] numBytes = value.numerator().toByteArray();
        byte[] denBytes = value.denominator().toByteArray();
        writeVarInt32(out, numBytes.length);
        out.write(numBytes, 0, numBytes.length);
        writeVarInt32(out, denBytes.length);
        out.write(denBytes, 0, denBytes.length);
    }

    static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

        int remaining() {
            return buf.length - pos;
        }

        int readUint8() {
            requireRemaining(1);
            return buf[pos++] & 0xFF;
        }

        int readInt32() {
            requireRemaining(4);
            int v = ((buf[pos] & 0xFF) << 24)
                    | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8)
                    | (buf[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long readInt64() {
            requireRemaining(8);
            long v = ((long) (buf[pos] & 0xFF) << 56)
                    | ((long) (buf[pos + 1] & 0xFF) << 48)
                    | ((long) (buf[pos + 2] & 0xFF) << 40)
                    | ((long) (buf[pos + 3] & 0xFF) << 32)
                    | ((long) (buf[pos + 4] & 0xFF) << 24)
                    | ((long) (buf[pos + 5] & 0xFF) << 16)
                    | ((long) (buf[pos + 6] & 0xFF) << 8)
                    | ((long) (buf[pos + 7] & 0xFF));
            pos += 8;
            return v;
        }

        UUID readUuid() {
            return new UUID(readInt64(), readInt64());
        }

        int readVarInt32() {
            int result = 0;
            int shift = 0;
            int bytesRead = 0;
            while (true) {
                if (pos >= buf.length) {
                    throw new IllegalArgumentException("Truncated varint32");
                }
                int b = buf[pos++] & 0xFF;
                bytesRead++;
                if (shift == 28 && (b & 0xF8) != 0) {
                    throw new IllegalArgumentException("Varint32 overflow");
                }
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (bytesRead > 1 && b == 0) {
                        throw new IllegalArgumentException("Non-minimal varint32 encoding");
                    }
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("Varint32 exceeds 5 bytes");
                }
            }
        }

        Fraction readFraction() {
            BigInteger num = readMinimalBigInteger();
            BigInteger den = readMinimalBigInteger();
            if (den.signum() <= 0) {
                throw new IllegalArgumentException("Non-positive fraction denominator: " + den);
            }
            Fraction fraction = new Fraction(num, den);
            if (!fraction.numerator().equals(num) || !fraction.denominator().equals(den)) {
                throw new IllegalArgumentException(
                        "Non-canonical fraction encoding: " + num + "/" + den
                                + " != " + fraction.numerator() + "/" + fraction.denominator());
            }
            return fraction;
        }

        private BigInteger readMinimalBigInteger() {
            int len = readVarInt32();
            if (len <= 0) {
                throw new IllegalArgumentException("BigInteger byte length must be positive: " + len);
            }
            requireRemaining(len);
            if (len > 1) {
                byte b0 = buf[pos];
                byte b1 = buf[pos + 1];
                if ((b0 == 0x00 && (b1 & 0x80) == 0) || (b0 == (byte) 0xFF && (b1 & 0x80) != 0)) {
                    throw new IllegalArgumentException("Non-minimal BigInteger two's-complement encoding");
                }
            }
            byte[] slice = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return new BigInteger(slice);
        }

        private void requireRemaining(int needed) {
            if (needed < 0 || remaining() < needed) {
                throw new IllegalArgumentException(
                        "Truncated binary payload: need " + needed + " bytes, remaining=" + remaining());
            }
        }
    }
}
