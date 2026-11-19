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
        PotBalanceContent emptyContent = PotBalanceBinaryCodec.encode(empty);
        assertEquals(1, emptyContent.schemaVersion());
        assertEquals(0, emptyContent.shareholdersCount());
        assertEquals(4, emptyContent.payload().length);
        assertEquals(empty, PotBalanceBinaryCodec.decode(potId, 1L, emptyContent));

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
        PotBalanceContent content = PotBalanceBinaryCodec.encode(original);
        assertEquals(5, content.shareholdersCount());
        assertEquals(1, content.schemaVersion());

        PotBalance decoded = PotBalanceBinaryCodec.decode(potId, 7L, content);
        assertEquals(original, decoded);
        // Re-encoding decoded must produce identical bytes and hash (bijection)
        PotBalanceContent reEncoded = PotBalanceBinaryCodec.encode(decoded);
        assertEquals(content, reEncoded);
        assertArrayEquals(content.payload(), reEncoded.payload());
        assertEquals(content.hash(), reEncoded.hash());
    }

    @Test
    void deterministicRegardlessOfCollectionOrderAndPotIdentity() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("80000000-0000-0000-0000-000000000002");
        UUID u3 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        Map<UUID, Fraction> orderA = new LinkedHashMap<>();
        orderA.put(u1, new Fraction(1, 3));
        orderA.put(u2, new Fraction(-1, 3));
        orderA.put(u3, Fraction.ZERO);

        Map<UUID, Fraction> orderB = new LinkedHashMap<>();
        orderB.put(u3, Fraction.ZERO);
        orderB.put(u2, new Fraction(-2, 6));
        orderB.put(u1, new Fraction(5, 15));

        PotBalanceContent c1 = PotBalanceBinaryCodec.encode(new PotBalance(UUID.randomUUID(), 1L, orderA));
        PotBalanceContent c2 = PotBalanceBinaryCodec.encode(new PotBalance(UUID.randomUUID(), 99L, orderB));
        PotBalanceContent c3 = PotBalanceBinaryCodec.encode(orderB);

        assertEquals(c1.hash(), c2.hash());
        assertEquals(c1.hash(), c3.hash());
        assertArrayEquals(c1.payload(), c2.payload());
        assertArrayEquals(c1.payload(), c3.payload());
        assertTrue(c1.exactContentEquals(c2));
    }

    @Test
    void domainSeparationBetweenBalanceAndAggregateHashes() {
        byte[] identicalPayload = new byte[8];
        ContentHash balHash = PotBalanceBinaryCodec.computeHash(1, identicalPayload);
        ContentHash aggHash = PotBalanceAggregateBinaryCodec.computeHash(1, identicalPayload);
        assertNotEquals(balHash, aggHash);
    }

    @Test
    void exactContentEqualsDetectsSingleBitDivergence() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotBalanceContent content = PotBalanceBinaryCodec.encode(
                Map.of(u1, new Fraction(1, 2), u2, new Fraction(-1, 2)));
        byte[] mutated = content.payload();
        mutated[mutated.length - 1] ^= 0x01;

        assertFalse(content.exactContentEquals(1, content.shareholdersCount(), mutated));
        assertFalse(content.exactContentEquals(1, content.shareholdersCount() + 1, content.payload()));
        assertFalse(content.exactContentEquals(2, content.shareholdersCount(), content.payload()));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(
                        UUID.randomUUID(), 1L, 1, content.shareholdersCount(), mutated, content.hash()));
    }

    @Test
    void strictDecodingRejectsInvalidFormats() {
        UUID potId = UUID.randomUUID();
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotBalanceContent valid = PotBalanceBinaryCodec.encode(
                Map.of(u1, new Fraction(1, 2), u2, new Fraction(-1, 2)));

        // Truncated payload
        byte[] truncated = Arrays.copyOf(valid.payload(), valid.payload().length - 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, truncated, null));

        // Trailing bytes
        byte[] trailing = Arrays.copyOf(valid.payload(), valid.payload().length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, trailing, null));

        // Unsupported schema version
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 2, 2, valid.payload(), null));

        // Mismatched shareholders count
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 3, valid.payload(), null));

        // Non-minimal positive BigInteger (leading 0x00 before 0x01)
        byte[] nonMinimalPos = rawBalancePayload(u1, new byte[]{0x00, 0x01}, new byte[]{0x01},
                u2, new byte[]{(byte) 0xFF}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, nonMinimalPos, null));

        // Non-minimal negative BigInteger (leading 0xFF before 0x80)
        byte[] nonMinimalNeg = rawBalancePayload(u1, new byte[]{0x00, (byte) 0x80}, new byte[]{0x01},
                u2, new byte[]{(byte) 0xFF, (byte) 0x80}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, nonMinimalNeg, null));

        // Non-reduced fraction (2/4)
        byte[] nonReduced = rawBalancePayload(u1, new byte[]{0x02}, new byte[]{0x04},
                u2, new byte[]{(byte) 0xFF}, new byte[]{0x02});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, nonReduced, null));

        // Non-canonical zero (0/2)
        byte[] badZero = rawBalancePayload(u1, new byte[]{0x00}, new byte[]{0x02},
                u2, new byte[]{0x00}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, badZero, null));

        // Negative denominator (1 / -2)
        byte[] negDen = rawBalancePayload(u1, new byte[]{0x01}, new byte[]{(byte) 0xFE},
                u2, new byte[]{0x01}, new byte[]{0x02});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, negDen, null));

        // Unbalanced vector (sum != 0)
        byte[] unbalanced = rawBalancePayload(u1, new byte[]{0x01}, new byte[]{0x01},
                u2, new byte[]{0x01}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, unbalanced, null));

        // Out-of-order UUIDs (u2 before u1)
        byte[] reversedOrder = rawBalancePayload(u2, new byte[]{0x01}, new byte[]{0x01},
                u1, new byte[]{(byte) 0xFF}, new byte[]{0x01});
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 2, reversedOrder, null));

        // Overlong varint32 (length 1 encoded as 0x81 0x00)
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, 1);
        PotBalanceBinaryCodec.writeUuid(out, u1);
        out.write(0x81);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x01);
        byte[] overlongVarint = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceBinaryCodec.decode(potId, 1L, 1, 1, overlongVarint, null));
    }

    private static byte[] rawBalancePayload(
            UUID u1, byte[] n1, byte[] d1, UUID u2, byte[] n2, byte[] d2) {
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, 2);
        PotBalanceBinaryCodec.writeUuid(out, u1);
        PotBalanceBinaryCodec.writeVarInt32(out, n1.length);
        out.write(n1, 0, n1.length);
        PotBalanceBinaryCodec.writeVarInt32(out, d1.length);
        out.write(d1, 0, d1.length);
        PotBalanceBinaryCodec.writeUuid(out, u2);
        PotBalanceBinaryCodec.writeVarInt32(out, n2.length);
        out.write(n2, 0, n2.length);
        PotBalanceBinaryCodec.writeVarInt32(out, d2.length);
        out.write(d2, 0, d2.length);
        return out.toByteArray();
    }
}
