package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PotBalanceBinaryCodecTest {

    @Test
    void roundTripEmptyAndComplexBalancesWithHugeFractionsAndUnsignedUuidOrder() {
        UUID potId = UUID.randomUUID();

        // Empty balance (0 shareholders)
        PotBalance empty = new PotBalance(potId, 1L, Map.of());
        PotBalanceBinaryCodec.EncodedBalance emptyEncoded = PotBalanceBinaryCodec.encode(empty);
        PotBalanceContent emptyContent = emptyEncoded.content();
        assertEquals(1, emptyContent.schemaVersion());
        assertEquals(0, emptyContent.shareholdersCount());
        assertEquals(4, emptyContent.payload().length);
        assertEquals(empty, PotBalanceBinaryCodec.decode(potId, 1L, emptyEncoded.shareholderSet(), emptyContent));

        // Unsigned UUID ordering edge cases: 0x7FFF... < 0x8000... in unsigned, reversed in signed!
        UUID uLow = new UUID(0x0000000000000001L, 0x0000000000000001L);
        UUID uMid = new UUID(0x7FFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
        UUID uHigh = new UUID(0x8000000000000000L, 0x0000000000000000L);
        UUID uMax = new UUID(0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
        assertTrue(SubobjectKey.compareUuid(uLow, uMid) < 0);
        assertTrue(SubobjectKey.compareUuid(uMid, uHigh) < 0);
        assertTrue(SubobjectKey.compareUuid(uHigh, uMax) < 0);

        BigInteger hugeNum = BigInteger.TEN.pow(45).add(BigInteger.valueOf(7));
        BigInteger hugeDen = BigInteger.TEN.pow(40).add(BigInteger.valueOf(3));
        Fraction fHugePos = new Fraction(hugeNum, hugeDen);
        Fraction fHugeNeg = new Fraction(hugeNum.negate(), hugeDen);
        Fraction fZero = Fraction.ZERO;
        Fraction fSmall = new Fraction(-128, 129); // -128 tests sign-bit boundary in two's complement
        Fraction fComp = fHugePos.add(fHugeNeg).add(fSmall).multiply(new Fraction(-1, 1));

        Map<UUID, Fraction> map = new LinkedHashMap<>();
        map.put(uMax, fZero);
        map.put(uHigh, fHugeNeg);
        map.put(uLow, fHugePos);
        map.put(uMid, fSmall);
        UUID uFifth = new UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        map.put(uFifth, fComp);

        PotBalance original = new PotBalance(potId, 7L, map);
        PotBalanceBinaryCodec.EncodedBalance encoded = PotBalanceBinaryCodec.encode(original);
        PotBalanceContent content = encoded.content();
        assertEquals(5, content.shareholdersCount());
        assertEquals(1, content.schemaVersion());
        assertEquals(encoded.shareholderSet().hash(), content.shareholderSetHash());

        PotBalance decoded = PotBalanceBinaryCodec.decode(potId, 7L, encoded.shareholderSet(), content);
        assertEquals(original, decoded);
        // Re-encoding decoded must produce identical bytes and hash (bijection)
        PotBalanceBinaryCodec.EncodedBalance reEncoded = PotBalanceBinaryCodec.encode(decoded);
        assertEquals(encoded.shareholderSet(), reEncoded.shareholderSet());
        assertEquals(content, reEncoded.content());
        assertArrayEquals(content.payload(), reEncoded.content().payload());
        assertEquals(content.hash(), reEncoded.content().hash());
    }

    @Test
    void deterministicRegardlessOfCollectionOrderAndPotIdentityAndDistinctAcrossShareholderSets() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("80000000-0000-0000-0000-000000000002");
        UUID u3 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID u4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID u5 = UUID.fromString("80000000-0000-0000-0000-000000000005");
        UUID u6 = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");

        Map<UUID, Fraction> orderA = new LinkedHashMap<>();
        orderA.put(u1, new Fraction(1, 3));
        orderA.put(u2, new Fraction(-1, 3));
        orderA.put(u3, Fraction.ZERO);

        Map<UUID, Fraction> orderB = new LinkedHashMap<>();
        orderB.put(u3, Fraction.ZERO);
        orderB.put(u2, new Fraction(-2, 6));
        orderB.put(u1, new Fraction(5, 15));

        PotBalanceBinaryCodec.EncodedBalance c1 = PotBalanceBinaryCodec.encode(new PotBalance(UUID.randomUUID(), 1L, orderA));
        PotBalanceBinaryCodec.EncodedBalance c2 = PotBalanceBinaryCodec.encode(new PotBalance(UUID.randomUUID(), 99L, orderB));
        PotBalanceBinaryCodec.EncodedBalance c3 = PotBalanceBinaryCodec.encode(orderB);

        assertEquals(c1.content().hash(), c2.content().hash());
        assertEquals(c1.content().hash(), c3.content().hash());
        assertArrayEquals(c1.content().payload(), c2.content().payload());
        assertArrayEquals(c1.content().payload(), c3.content().payload());
        assertTrue(c1.content().exactContentEquals(c2.content()));

        // Identical financial fractions on DIFFERENT shareholder sets MUST produce DIFFERENT content_hash!
        Map<UUID, Fraction> otherSet = new LinkedHashMap<>();
        otherSet.put(u4, new Fraction(1, 3));
        otherSet.put(u5, new Fraction(-1, 3));
        otherSet.put(u6, Fraction.ZERO);
        PotBalanceBinaryCodec.EncodedBalance cOther = PotBalanceBinaryCodec.encode(otherSet);
        assertArrayEquals(c1.content().payload(), cOther.content().payload());
        assertNotEquals(c1.shareholderSet().hash(), cOther.shareholderSet().hash());
        assertNotEquals(c1.content().hash(), cOther.content().hash());
        assertFalse(c1.content().exactContentEquals(cOther.content()));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(UUID.randomUUID(), 1L, cOther.shareholderSet(), c1.content()));
    }

    @Test
    void domainSeparationBetweenBalanceAndAggregateHashes() {
        byte[] identicalPayload = new byte[8];
        ContentHash shHash = PotShareholderSetBinaryCodec.computeHash(1, new byte[4]);
        ContentHash balHash = PotBalanceBinaryCodec.computeHash(1, shHash, identicalPayload);
        ContentHash aggHash = PotBalanceAggregateBinaryCodec.computeHash(1, shHash, identicalPayload);
        assertNotEquals(balHash, aggHash);
    }

    @Test
    void exactContentEqualsDetectsSingleBitDivergence() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotBalanceBinaryCodec.EncodedBalance encoded = PotBalanceBinaryCodec.encode(
                Map.of(u1, new Fraction(1, 2), u2, new Fraction(-1, 2)));
        PotBalanceContent content = encoded.content();
        byte[] mutated = content.payload();
        mutated[mutated.length - 1] ^= 0x01;
        ContentHash otherSetHash = PotShareholderSetBinaryCodec.encode(
                List.of(u1, UUID.fromString("00000000-0000-0000-0000-000000000003"))).hash();

        assertFalse(content.exactContentEquals(1, content.shareholdersCount(), content.shareholderSetHash(), mutated));
        assertFalse(content.exactContentEquals(1, content.shareholdersCount(), otherSetHash, content.payload()));
        assertFalse(content.exactContentEquals(1, content.shareholdersCount() + 1, content.shareholderSetHash(), content.payload()));
        assertFalse(content.exactContentEquals(2, content.shareholdersCount(), content.shareholderSetHash(), content.payload()));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(
                        UUID.randomUUID(), 1L, encoded.shareholderSet(),
                        1, content.shareholdersCount(), mutated, content.hash()));
    }

    @Test
    void strictDecodingRejectsInvalidFormats() {
        UUID potId = UUID.randomUUID();
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotBalanceBinaryCodec.EncodedBalance validEnc = PotBalanceBinaryCodec.encode(
                Map.of(u1, new Fraction(1, 2), u2, new Fraction(-1, 2)));
        PotShareholderSet set2 = validEnc.shareholderSet();
        PotBalanceContent valid = validEnc.content();

        // Truncated payload
        byte[] truncated = Arrays.copyOf(valid.payload(), valid.payload().length - 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, truncated, null));

        // Trailing bytes
        byte[] trailing = Arrays.copyOf(valid.payload(), valid.payload().length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, trailing, null));

        // Unsupported schema version
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 2, 2, valid.payload(), null));

        // Mismatched shareholders count
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 3, valid.payload(), null));

        // Non-minimal positive BigInteger (leading 0x00 before 0x01)
        byte[] nonMinimalPos = rawBalancePayload(new byte[]{0x00, 0x01}, new byte[]{0x01},
                new byte[]{(byte) 0xFF}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, nonMinimalPos, null));

        // Non-minimal negative BigInteger (leading 0xFF before 0x80)
        byte[] nonMinimalNeg = rawBalancePayload(new byte[]{0x00, (byte) 0x80}, new byte[]{0x01},
                new byte[]{(byte) 0xFF, (byte) 0x80}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, nonMinimalNeg, null));

        // Non-reduced fraction (2/4)
        byte[] nonReduced = rawBalancePayload(new byte[]{0x02}, new byte[]{0x04},
                new byte[]{(byte) 0xFF}, new byte[]{0x02});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, nonReduced, null));

        // Non-canonical zero (0/2)
        byte[] badZero = rawBalancePayload(new byte[]{0x00}, new byte[]{0x02},
                new byte[]{0x00}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, badZero, null));

        // Negative denominator (1 / -2)
        byte[] negDen = rawBalancePayload(new byte[]{0x01}, new byte[]{(byte) 0xFE},
                new byte[]{0x01}, new byte[]{0x02});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, negDen, null));

        // Unbalanced vector (sum != 0)
        byte[] unbalanced = rawBalancePayload(new byte[]{0x01}, new byte[]{0x01},
                new byte[]{0x01}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set2, 1, 2, unbalanced, null));

        // Overlong varint32 (length 1 encoded as 0x81 0x00)
        PotShareholderSet set1 = PotShareholderSetBinaryCodec.encode(List.of(u1));
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, 1);
        out.write(0x81);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x01);
        byte[] overlongVarint = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, set1, 1, 1, overlongVarint, null));
    }

    private static byte[] rawBalancePayload(byte[] n1, byte[] d1, byte[] n2, byte[] d2) {
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, 2);
        PotBalanceBinaryCodec.writeVarInt32(out, n1.length);
        out.write(n1, 0, n1.length);
        PotBalanceBinaryCodec.writeVarInt32(out, d1.length);
        out.write(d1, 0, d1.length);
        PotBalanceBinaryCodec.writeVarInt32(out, n2.length);
        out.write(n2, 0, n2.length);
        PotBalanceBinaryCodec.writeVarInt32(out, d2.length);
        out.write(d2, 0, d2.length);
        return out.toByteArray();
    }
}
