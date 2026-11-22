package com.kartaguez.pocoma.domain.projection.radix;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable 32-byte SHA-256 content address with unsigned lexicographic ordering. */
public final class ContentHash implements Comparable<ContentHash> {
    public static final int BYTE_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;
    private final int hashCode;

    private ContentHash(byte[] ownedBytes) {
        this.bytes = ownedBytes;
        this.hashCode = Arrays.hashCode(ownedBytes);
    }

    public static ContentHash of(byte[] bytes) {
        Objects.requireNonNull(bytes, "Hash bytes required");
        if (bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("Expected 32-byte SHA-256 hash, got " + bytes.length);
        }
        return new ContentHash(bytes.clone());
    }

    static ContentHash wrapOwned(byte[] ownedBytes) {
        if (ownedBytes == null || ownedBytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("Expected 32-byte SHA-256 hash");
        }
        return new ContentHash(ownedBytes);
    }

    public static ContentHash fromHex(String hex) {
        Objects.requireNonNull(hex, "Hex string required");
        if (hex.length() != BYTE_LENGTH * 2) {
            throw new IllegalArgumentException("Expected 64-character hex hash, got " + hex.length());
        }
        return new ContentHash(HEX.parseHex(hex));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    byte[] rawBytes() {
        return bytes;
    }

    public String hex() {
        return HEX.formatHex(bytes);
    }

    @Override
    public int compareTo(ContentHash other) {
        return Arrays.compareUnsigned(this.bytes, other.bytes);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ContentHash other && Arrays.equals(this.bytes, other.bytes));
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return hex();
    }
}
