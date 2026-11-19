package com.kartaguez.pocoma.domain.projection.radix;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Radix16CodecTest {

    @Test
    void shareholderRoundTripAndFlags() {
        UUID id = UUID.fromString("8f123456-789a-4bc0-def1-23456789abcd");
        PotVersion.Shareholder s1 = new PotVersion.Shareholder(id, 1, 2, 3, false, false);
        PotVersion.Shareholder s2 = new PotVersion.Shareholder(id, 10, 20, 30, true, false);
        PotVersion.Shareholder s3 = new PotVersion.Shareholder(id, 11, 22, 33, false, true);
        PotVersion.Shareholder s4 = new PotVersion.Shareholder(id, 12, 24, 36, true, true);

        for (PotVersion.Shareholder s : List.of(s1, s2, s3, s4)) {
            Radix16Codec.EncodedEntry encoded = Radix16Codec.encodeShareholder(s);
            assertEquals(Radix16Codec.ENTRY_BYTES, encoded.bytes().length);
            assertEquals(s, Radix16Codec.decodeShareholder(encoded.bytes(), 0));
        }
    }

    @Test
    void expenseRoundTripAndFlags() {
        UUID id = UUID.fromString("00000001-ffff-4000-8000-abcdef012345");
        PotVersion.Expense e1 = new PotVersion.Expense(id, 1, 2, 3, false);
        PotVersion.Expense e2 = new PotVersion.Expense(id, 99, 100, 101, true);

        for (PotVersion.Expense e : List.of(e1, e2)) {
            Radix16Codec.EncodedEntry encoded = Radix16Codec.encodeExpense(e);
            assertEquals(Radix16Codec.ENTRY_BYTES, encoded.bytes().length);
            assertEquals(e, Radix16Codec.decodeExpense(encoded.bytes(), 0));
        }
    }

    @Test
    void rejectsInvalidLengthsFlagsAndNonPositiveVersions() {
        assertThrows(IllegalArgumentException.class, () -> Radix16Codec.decodeShareholder(new byte[40], 0));
        assertThrows(IllegalArgumentException.class, () -> Radix16Codec.decodeExpense(new byte[40], 0));

        // Unknown flags on shareholder (bit 2 set)
        byte[] badSh = Radix16Codec.encodeShareholder(
                new PotVersion.Shareholder(UUID.randomUUID(), 1, 1, 1, false, false)).bytes();
        badSh[40] = 0x04;
        assertThrows(IllegalArgumentException.class, () -> Radix16Codec.decodeShareholder(badSh, 0));

        // Unknown flags on expense (bit 1 set)
        byte[] badEx = Radix16Codec.encodeExpense(
                new PotVersion.Expense(UUID.randomUUID(), 1, 1, 1, false)).bytes();
        badEx[40] = 0x02;
        assertThrows(IllegalArgumentException.class, () -> Radix16Codec.decodeExpense(badEx, 0));

        // Non-positive version
        byte[] zeroVer = Radix16Codec.encodeExpense(
                new PotVersion.Expense(UUID.randomUUID(), 1, 1, 1, false)).bytes();
        ByteBuffer.wrap(zeroVer, 16, 8).putLong(0L);
        assertThrows(IllegalArgumentException.class, () -> Radix16Codec.decodeExpense(zeroVer, 0));
    }

    @Test
    void rejectsNonCanonicalLeafOrderAndSupportsBinarySearch() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertTrue(SubobjectKey.compareUuid(low, high) < 0);

        Radix16Codec.EncodedEntry eLow = Radix16Codec.encodeExpense(new PotVersion.Expense(low, 1, 1, 1, false));
        Radix16Codec.EncodedEntry eHigh = Radix16Codec.encodeExpense(new PotVersion.Expense(high, 2, 2, 2, true));

        assertThrows(IllegalArgumentException.class,
                () -> Radix16Codec.makeLeaf(Radix16Codec.TREE_EXPENSES, List.of(eHigh, eLow)));
        assertThrows(IllegalArgumentException.class,
                () -> Radix16Codec.makeLeaf(Radix16Codec.TREE_EXPENSES, List.of(eLow, eLow)));

        Radix16Node leaf = Radix16Codec.makeLeaf(Radix16Codec.TREE_EXPENSES, List.of(eLow, eHigh));
        assertEquals(Optional.of(new PotVersion.Expense(low, 1, 1, 1, false)),
                Radix16Codec.findExpenseInLeaf(leaf, low));
        assertEquals(Optional.of(new PotVersion.Expense(high, 2, 2, 2, true)),
                Radix16Codec.findExpenseInLeaf(leaf, high));
        assertEquals(Optional.empty(),
                Radix16Codec.findExpenseInLeaf(leaf, UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")));
    }

    @Test
    void hashDomainSeparationBetweenTreeKindsAndPotUserSet() {
        UUID id = UUID.fromString("12345678-1234-4234-8234-1234567890ab");
        Radix16Codec.EncodedEntry sh = Radix16Codec.encodeShareholder(
                new PotVersion.Shareholder(id, 1, 2, 3, false, false));
        Radix16Codec.EncodedEntry ex = Radix16Codec.encodeExpense(
                new PotVersion.Expense(id, 1, 2, 3, false));
        assertArrayEquals(sh.bytes(), ex.bytes());

        Radix16Node shLeaf = Radix16Codec.makeLeaf(Radix16Codec.TREE_SHAREHOLDERS, List.of(sh));
        Radix16Node exLeaf = Radix16Codec.makeLeaf(Radix16Codec.TREE_EXPENSES, List.of(ex));
        assertNotEquals(shLeaf.hash(), exLeaf.hash());

        // PotUserSet determinism and canonical sorting
        assertTrue(PotUserSet.of(List.of()).isEmpty());
        UUID u1 = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PotUserSet setA = PotUserSet.of(List.of(u1, u2, u1)).orElseThrow();
        PotUserSet setB = PotUserSet.of(List.of(u2, u1)).orElseThrow();
        assertEquals(setA.hash(), setB.hash());
        assertEquals(List.of(u2, u1), setA.userIds());
        assertTrue(setA.verifyHash());
        assertTrue(setA.exactContentEquals(setB));
    }

    @Test
    void contentHashValidationAndOrdering() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        a[0] = 0x01;
        b[0] = (byte) 0xFF;
        ContentHash ha = ContentHash.of(a);
        ContentHash hb = ContentHash.of(b);
        assertTrue(ha.compareTo(hb) < 0);
        assertEquals(ha, ContentHash.fromHex(ha.hex()));
        assertThrows(IllegalArgumentException.class, () -> ContentHash.of(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> ContentHash.fromHex("abcd"));
    }
}
