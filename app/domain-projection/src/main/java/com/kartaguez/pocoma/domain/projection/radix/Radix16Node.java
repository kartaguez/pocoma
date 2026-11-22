package com.kartaguez.pocoma.domain.projection.radix;

import java.nio.ByteBuffer;
import java.util.*;

/** Immutable Radix-16 tree node (either internal 'I' or grouped leaf 'L'). */
public final class Radix16Node {
    private final ContentHash hash;
    private final int schemaVersion;
    private final char nodeKind;
    private final char treeKind;
    private final int entryCount;
    private final String prefix;
    private final List<ContentHash> childHashes;
    private final byte[] payload;

    public Radix16Node(
            ContentHash hash,
            int schemaVersion,
            char nodeKind,
            char treeKind,
            int entryCount,
            String prefix,
            List<ContentHash> childHashes,
            byte[] payload) {
        this.hash = Objects.requireNonNull(hash, "hash required");
        if (schemaVersion != Radix16Codec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported node schemaVersion: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        if (nodeKind != Radix16Codec.NODE_LEAF && nodeKind != Radix16Codec.NODE_INTERNAL) {
            throw new IllegalArgumentException("Invalid nodeKind: " + nodeKind);
        }
        this.nodeKind = nodeKind;
        if (treeKind != Radix16Codec.TREE_SHAREHOLDERS && treeKind != Radix16Codec.TREE_EXPENSES) {
            throw new IllegalArgumentException("Invalid treeKind: " + treeKind);
        }
        this.treeKind = treeKind;
        Objects.requireNonNull(prefix, "prefix required");
        Objects.requireNonNull(payload, "payload required");

        if (nodeKind == Radix16Codec.NODE_LEAF) {
            if (!prefix.isEmpty()) {
                throw new IllegalArgumentException("Leaf node must have empty prefix");
            }
            if (childHashes != null) {
                throw new IllegalArgumentException("Leaf node must have null childHashes");
            }
            if (entryCount < 1 || entryCount > Radix16Tree.MAX_LEAF_SIZE) {
                throw new IllegalArgumentException("Leaf entryCount out of bounds: " + entryCount);
            }
            if (payload.length != entryCount * Radix16Codec.ENTRY_BYTES) {
                throw new IllegalArgumentException(
                        "Leaf payload length mismatch: expected " + (entryCount * Radix16Codec.ENTRY_BYTES) + ", got " + payload.length);
            }
            this.childHashes = null;
        } else {
            validatePrefix(prefix);
            if (childHashes == null || childHashes.size() < 2 || childHashes.size() > 16) {
                throw new IllegalArgumentException("Internal node must have 2..16 children");
            }
            if (entryCount <= Radix16Tree.MAX_LEAF_SIZE) {
                throw new IllegalArgumentException("Internal node entryCount must exceed " + Radix16Tree.MAX_LEAF_SIZE);
            }
            int k = childHashes.size();
            if (payload.length != 2 + k * 4) {
                throw new IllegalArgumentException("Internal payload length mismatch");
            }
            Set<ContentHash> distinct = new HashSet<>(k * 2);
            for (ContentHash child : childHashes) {
                Objects.requireNonNull(child, "childHash required");
                if (child.equals(hash)) {
                    throw new IllegalArgumentException("Internal node self-reference forbidden");
                }
                if (!distinct.add(child)) {
                    throw new IllegalArgumentException("Internal node duplicate childHash forbidden");
                }
            }
            int mask = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (Integer.bitCount(mask) != k) {
                throw new IllegalArgumentException("Internal nibbleMask bit count does not match child count");
            }
            ByteBuffer bb = ByteBuffer.wrap(payload, 2, k * 4);
            long sum = 0;
            for (int i = 0; i < k; i++) {
                int c = bb.getInt();
                if (c <= 0) {
                    throw new IllegalArgumentException("Child entry count must be positive");
                }
                sum += c;
            }
            if (sum != entryCount) {
                throw new IllegalArgumentException("Internal entryCount does not match sum of child counts");
            }
            this.childHashes = List.copyOf(childHashes);
        }

        this.entryCount = entryCount;
        this.prefix = prefix;
        this.payload = payload.clone();
    }

    private static void validatePrefix(String prefix) {
        if (prefix.length() > 31) {
            throw new IllegalArgumentException("Prefix exceeds max length 31: " + prefix.length());
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            boolean valid = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!valid) {
                throw new IllegalArgumentException("Invalid hex nibble in prefix: " + prefix);
            }
        }
    }

    public ContentHash hash() {
        return hash;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public char nodeKind() {
        return nodeKind;
    }

    public char treeKind() {
        return treeKind;
    }

    public int entryCount() {
        return entryCount;
    }

    public String prefix() {
        return prefix;
    }

    public List<ContentHash> childHashes() {
        return childHashes;
    }

    public byte[] payload() {
        return payload.clone();
    }

    byte[] rawPayload() {
        return payload;
    }

    public boolean isLeaf() {
        return nodeKind == Radix16Codec.NODE_LEAF;
    }

    public boolean isInternal() {
        return nodeKind == Radix16Codec.NODE_INTERNAL;
    }

    public int nibbleMask() {
        if (!isInternal()) {
            throw new IllegalStateException("Not an internal node");
        }
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    public int[] childEntryCounts() {
        if (!isInternal()) {
            throw new IllegalStateException("Not an internal node");
        }
        int k = childHashes.size();
        int[] counts = new int[k];
        ByteBuffer bb = ByteBuffer.wrap(payload, 2, k * 4);
        for (int i = 0; i < k; i++) {
            counts[i] = bb.getInt();
        }
        return counts;
    }

    /** Returns index in {@link #childHashes()} for nibble (0..15), or -1 if absent. */
    public int childIndexForNibble(int nibble) {
        if (nibble < 0 || nibble > 15) {
            throw new IllegalArgumentException("Nibble out of range: " + nibble);
        }
        int mask = nibbleMask();
        int bit = 1 << nibble;
        if ((mask & bit) == 0) {
            return -1;
        }
        return Integer.bitCount(mask & (bit - 1));
    }

    public int[] presentNibbles() {
        int mask = nibbleMask();
        int[] nibbles = new int[Integer.bitCount(mask)];
        int idx = 0;
        for (int n = 0; n < 16; n++) {
            if ((mask & (1 << n)) != 0) {
                nibbles[idx++] = n;
            }
        }
        return nibbles;
    }

    public boolean exactContentEquals(Radix16Node other) {
        if (other == null) {
            return false;
        }
        return schemaVersion == other.schemaVersion
                && nodeKind == other.nodeKind
                && treeKind == other.treeKind
                && entryCount == other.entryCount
                && prefix.equals(other.prefix)
                && Objects.equals(childHashes, other.childHashes)
                && Arrays.equals(payload, other.payload)
                && hash.equals(other.hash);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof Radix16Node other && exactContentEquals(other));
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(hash, schemaVersion, nodeKind, treeKind, entryCount, prefix, childHashes);
        return 31 * result + Arrays.hashCode(payload);
    }
}
