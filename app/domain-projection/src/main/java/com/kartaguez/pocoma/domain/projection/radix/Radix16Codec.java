package com.kartaguez.pocoma.domain.projection.radix;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned binary codec (41 bytes/entry) and SHA-256 hashing for Radix-16 PotVersion nodes. */
public final class Radix16Codec {
    public static final int SCHEMA_VERSION = 1;
    public static final int ENTRY_BYTES = 41;
    public static final char NODE_LEAF = 'L';
    public static final char NODE_INTERNAL = 'I';
    public static final char TREE_SHAREHOLDERS = 'S';
    public static final char TREE_EXPENSES = 'E';

    private static final int FLAG_DELETED = 0x01;
    private static final int FLAG_FREE = 0x02;
    private static final int SHAREHOLDER_ALLOWED_FLAGS = FLAG_DELETED | FLAG_FREE;
    private static final int EXPENSE_ALLOWED_FLAGS = FLAG_DELETED;

    private static final byte[] HASH_DOMAIN = "pocoma-r16-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private Radix16Codec() {}

    public record EncodedEntry(UUID id, byte[] bytes) {
        public EncodedEntry {
            Objects.requireNonNull(id, "id required");
            Objects.requireNonNull(bytes, "bytes required");
            if (bytes.length != ENTRY_BYTES) {
                throw new IllegalArgumentException("Expected " + ENTRY_BYTES + " bytes, got " + bytes.length);
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        byte[] rawBytes() {
            return bytes;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof EncodedEntry other && id.equals(other.id) && Arrays.equals(bytes, other.bytes));
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + Arrays.hashCode(bytes);
        }
    }

    public static EncodedEntry encodeShareholder(PotVersion.Shareholder shareholder) {
        Objects.requireNonNull(shareholder, "shareholder required");
        byte[] buf = new byte[ENTRY_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putLong(shareholder.id().getMostSignificantBits());
        bb.putLong(shareholder.id().getLeastSignificantBits());
        bb.putLong(shareholder.headerVersion());
        bb.putLong(shareholder.detailsVersion());
        bb.putLong(shareholder.weightVersion());
        byte flags = (byte) ((shareholder.deleted() ? FLAG_DELETED : 0) | (shareholder.isFree() ? FLAG_FREE : 0));
        bb.put(flags);
        return new EncodedEntry(shareholder.id(), buf);
    }

    public static PotVersion.Shareholder decodeShareholder(byte[] buf, int offset) {
        checkSlice(buf, offset);
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, ENTRY_BYTES);
        UUID id = new UUID(bb.getLong(), bb.getLong());
        long headerVersion = bb.getLong();
        long detailsVersion = bb.getLong();
        long weightVersion = bb.getLong();
        int flags = bb.get() & 0xFF;
        if ((flags & ~SHAREHOLDER_ALLOWED_FLAGS) != 0) {
            throw new IllegalArgumentException("Unknown shareholder flags: 0x" + Integer.toHexString(flags));
        }
        return new PotVersion.Shareholder(
                id,
                headerVersion,
                detailsVersion,
                weightVersion,
                (flags & FLAG_DELETED) != 0,
                (flags & FLAG_FREE) != 0);
    }

    public static EncodedEntry encodeExpense(PotVersion.Expense expense) {
        Objects.requireNonNull(expense, "expense required");
        byte[] buf = new byte[ENTRY_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putLong(expense.id().getMostSignificantBits());
        bb.putLong(expense.id().getLeastSignificantBits());
        bb.putLong(expense.headerVersion());
        bb.putLong(expense.detailsVersion());
        bb.putLong(expense.financialsVersion());
        byte flags = (byte) (expense.deleted() ? FLAG_DELETED : 0);
        bb.put(flags);
        return new EncodedEntry(expense.id(), buf);
    }

    public static PotVersion.Expense decodeExpense(byte[] buf, int offset) {
        checkSlice(buf, offset);
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, ENTRY_BYTES);
        UUID id = new UUID(bb.getLong(), bb.getLong());
        long headerVersion = bb.getLong();
        long detailsVersion = bb.getLong();
        long financialsVersion = bb.getLong();
        int flags = bb.get() & 0xFF;
        if ((flags & ~EXPENSE_ALLOWED_FLAGS) != 0) {
            throw new IllegalArgumentException("Unknown expense flags: 0x" + Integer.toHexString(flags));
        }
        return new PotVersion.Expense(
                id,
                headerVersion,
                detailsVersion,
                financialsVersion,
                (flags & FLAG_DELETED) != 0);
    }

    public static List<EncodedEntry> decodeLeafEntries(Radix16Node leaf) {
        Objects.requireNonNull(leaf, "leaf required");
        if (!leaf.isLeaf()) {
            throw new IllegalArgumentException("Expected leaf node");
        }
        int count = leaf.entryCount();
        byte[] payload = leaf.rawPayload();
        if (payload.length != count * ENTRY_BYTES) {
            throw new IllegalStateException("Corrupt leaf payload length");
        }
        List<EncodedEntry> entries = new ArrayList<>(count);
        UUID prev = null;
        for (int i = 0; i < count; i++) {
            int offset = i * ENTRY_BYTES;
            UUID id = readUuid(payload, offset);
            if (prev != null && SubobjectKey.compareUuid(prev, id) >= 0) {
                throw new IllegalStateException("Non-canonical leaf UUID order");
            }
            prev = id;
            byte[] slice = Arrays.copyOfRange(payload, offset, offset + ENTRY_BYTES);
            entries.add(new EncodedEntry(id, slice));
        }
        return entries;
    }

    public static int findEntryOffsetInLeaf(Radix16Node leaf, UUID targetId) {
        Objects.requireNonNull(leaf, "leaf required");
        Objects.requireNonNull(targetId, "targetId required");
        if (!leaf.isLeaf()) {
            throw new IllegalArgumentException("Expected leaf node");
        }
        byte[] payload = leaf.rawPayload();
        int low = 0;
        int high = leaf.entryCount() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int offset = mid * ENTRY_BYTES;
            UUID midId = readUuid(payload, offset);
            int cmp = SubobjectKey.compareUuid(midId, targetId);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return offset;
            }
        }
        return -1;
    }

    public static Optional<PotVersion.Shareholder> findShareholderInLeaf(Radix16Node leaf, UUID targetId) {
        if (leaf.treeKind() != TREE_SHAREHOLDERS) {
            throw new IllegalArgumentException("Expected shareholder leaf");
        }
        int offset = findEntryOffsetInLeaf(leaf, targetId);
        return offset < 0 ? Optional.empty() : Optional.of(decodeShareholder(leaf.rawPayload(), offset));
    }

    public static Optional<PotVersion.Expense> findExpenseInLeaf(Radix16Node leaf, UUID targetId) {
        if (leaf.treeKind() != TREE_EXPENSES) {
            throw new IllegalArgumentException("Expected expense leaf");
        }
        int offset = findEntryOffsetInLeaf(leaf, targetId);
        return offset < 0 ? Optional.empty() : Optional.of(decodeExpense(leaf.rawPayload(), offset));
    }

    public static ContentHash computeNodeHash(
            int schemaVersion,
            char treeKind,
            char nodeKind,
            int entryCount,
            String prefix,
            List<ContentHash> childHashes,
            byte[] payload) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(HASH_DOMAIN);
            md.update((byte) schemaVersion);
            md.update((byte) treeKind);
            md.update((byte) nodeKind);
            ByteBuffer hdr = ByteBuffer.allocate(5);
            hdr.putInt(entryCount);
            hdr.put((byte) prefix.length());
            md.update(hdr.array());
            if (!prefix.isEmpty()) {
                md.update(prefix.getBytes(StandardCharsets.US_ASCII));
            }
            md.update(payload);
            if (childHashes != null) {
                for (ContentHash child : childHashes) {
                    md.update(child.rawBytes());
                }
            }
            return ContentHash.wrapOwned(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    public static Radix16Node makeLeaf(char treeKind, List<EncodedEntry> sortedEntries) {
        Objects.requireNonNull(sortedEntries, "sortedEntries required");
        int count = sortedEntries.size();
        if (count < 1 || count > Radix16Tree.MAX_LEAF_SIZE) {
            throw new IllegalArgumentException("Leaf size out of bounds: " + count);
        }
        byte[] payload = new byte[count * ENTRY_BYTES];
        UUID prev = null;
        for (int i = 0; i < count; i++) {
            EncodedEntry entry = sortedEntries.get(i);
            if (prev != null && SubobjectKey.compareUuid(prev, entry.id()) >= 0) {
                throw new IllegalArgumentException("Leaf entries must be strictly sorted by unsigned UUID");
            }
            prev = entry.id();
            System.arraycopy(entry.rawBytes(), 0, payload, i * ENTRY_BYTES, ENTRY_BYTES);
        }
        ContentHash hash = computeNodeHash(SCHEMA_VERSION, treeKind, NODE_LEAF, count, "", null, payload);
        return new Radix16Node(hash, SCHEMA_VERSION, NODE_LEAF, treeKind, count, "", null, payload);
    }

    public static Radix16Node makeInternal(
            char treeKind,
            String prefix,
            int nibbleMask,
            List<ContentHash> childHashes,
            int[] childEntryCounts) {
        int k = childHashes.size();
        if (k < 2 || k > 16 || Integer.bitCount(nibbleMask) != k || childEntryCounts.length != k) {
            throw new IllegalArgumentException("Invalid internal node children");
        }
        int totalEntries = 0;
        byte[] payload = new byte[2 + k * 4];
        payload[0] = (byte) ((nibbleMask >>> 8) & 0xFF);
        payload[1] = (byte) (nibbleMask & 0xFF);
        ByteBuffer bb = ByteBuffer.wrap(payload, 2, k * 4);
        for (int i = 0; i < k; i++) {
            if (childEntryCounts[i] <= 0) {
                throw new IllegalArgumentException("Child entry count must be positive");
            }
            totalEntries += childEntryCounts[i];
            bb.putInt(childEntryCounts[i]);
        }
        List<ContentHash> immutableChildren = List.copyOf(childHashes);
        ContentHash hash = computeNodeHash(SCHEMA_VERSION, treeKind, NODE_INTERNAL, totalEntries, prefix, immutableChildren, payload);
        return new Radix16Node(hash, SCHEMA_VERSION, NODE_INTERNAL, treeKind, totalEntries, prefix, immutableChildren, payload);
    }

    public static int uuidNibble(UUID id, int depth) {
        if (depth < 0 || depth >= 32) {
            throw new IllegalArgumentException("Nibble depth out of range: " + depth);
        }
        long bits = depth < 16 ? id.getMostSignificantBits() : id.getLeastSignificantBits();
        int shift = (15 - (depth & 15)) * 4;
        return (int) ((bits >>> shift) & 0xFL);
    }

    public static char nibbleToHex(int nibble) {
        if (nibble < 0 || nibble > 15) {
            throw new IllegalArgumentException("Nibble out of range: " + nibble);
        }
        return HEX_DIGITS[nibble];
    }

    public static int hexToNibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return 10 + (c - 'a');
        }
        throw new IllegalArgumentException("Invalid lowercase hex nibble: " + c);
    }

    public static UUID readUuid(byte[] buf, int offset) {
        if (buf == null || offset < 0 || offset + 16 > buf.length) {
            throw new IllegalArgumentException("Buffer too short for UUID at offset " + offset);
        }
        long msb = ByteBuffer.wrap(buf, offset, 8).getLong();
        long lsb = ByteBuffer.wrap(buf, offset + 8, 8).getLong();
        return new UUID(msb, lsb);
    }

    private static void checkSlice(byte[] buf, int offset) {
        if (buf == null || offset < 0 || offset + ENTRY_BYTES > buf.length) {
            throw new IllegalArgumentException("Invalid encoded entry slice at offset " + offset);
        }
    }
}
