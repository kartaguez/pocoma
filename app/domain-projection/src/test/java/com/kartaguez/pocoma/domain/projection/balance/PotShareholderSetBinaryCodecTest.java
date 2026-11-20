package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PotShareholderSetBinaryCodecTest {

    @Test
    void roundTripEmptyAndUnsignedUuidOrderCanonical() {
        PotShareholderSet empty = PotShareholderSetBinaryCodec.encode(List.of());
        assertEquals(1, empty.schemaVersion());
        assertEquals(0, empty.shareholdersCount());
        assertEquals(4, empty.payload().length);
        assertTrue(empty.shareholderIds().isEmpty());
        PotShareholderSet decodedEmpty = PotShareholderSetBinaryCodec.decode(
                1, 0, empty.payload(), empty.hash());
        assertEquals(empty, decodedEmpty);

        UUID uLow = new UUID(0x0000000000000001L, 0x0000000000000001L);
        UUID uMid = new UUID(0x7FFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
        UUID uHigh = new UUID(0x8000000000000000L, 0x0000000000000000L);
        UUID uMax = new UUID(0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);

        PotShareholderSet fromUnordered = PotShareholderSetBinaryCodec.encode(
                List.of(uMax, uHigh, uLow, uMid));
        PotShareholderSet fromCanonical = PotShareholderSetBinaryCodec.encodeCanonical(
                List.of(uLow, uMid, uHigh, uMax));

        assertEquals(List.of(uLow, uMid, uHigh, uMax), fromUnordered.shareholderIds());
        assertEquals(fromCanonical.hash(), fromUnordered.hash());
        assertArrayEquals(fromCanonical.payload(), fromUnordered.payload());
        assertEquals(4 + 4 * 16, fromUnordered.payload().length);

        PotShareholderSet decoded = PotShareholderSetBinaryCodec.decode(
                1, 4, fromUnordered.payload(), fromUnordered.hash());
        assertEquals(fromUnordered, decoded);
        assertTrue(fromUnordered.exactContentEquals(decoded));
    }

    @Test
    void domainSeparationFromBalanceAndAggregateHashes() {
        byte[] payload = new byte[4];
        ContentHash shHash = PotShareholderSetBinaryCodec.computeHash(1, payload);
        ContentHash balHash = PotBalanceBinaryCodec.computeHash(1, shHash, payload);
        ContentHash aggHash = PotBalanceAggregateBinaryCodec.computeHash(1, shHash, new byte[8]);
        assertNotEquals(shHash, balHash);
        assertNotEquals(shHash, aggHash);
        assertNotEquals(balHash, aggHash);
    }

    @Test
    void rejectsDuplicatesUnsortedAndCorruptedPayloads() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("80000000-0000-0000-0000-000000000002");
        assertTrue(SubobjectKey.compareUuid(u1, u2) < 0);

        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.encode(List.of(u1, u1)));
        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.encodeCanonical(List.of(u2, u1)));

        PotShareholderSet valid = PotShareholderSetBinaryCodec.encode(List.of(u1, u2));
        byte[] mutated = valid.payload();
        mutated[mutated.length - 1] ^= 0x01;
        assertFalse(valid.exactContentEquals(1, 2, mutated));
        assertFalse(valid.exactContentEquals(1, 3, valid.payload()));
        assertFalse(valid.exactContentEquals(2, 2, valid.payload()));
        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.decode(1, 2, mutated, valid.hash()));

        // Wrong payload length
        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.decode(1, 2, Arrays.copyOf(valid.payload(), 20), null));
        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.decode(1, 3, valid.payload(), null));

        // Out-of-order UUIDs in raw payload
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, 2);
        PotBalanceBinaryCodec.writeUuid(out, u2);
        PotBalanceBinaryCodec.writeUuid(out, u1);
        byte[] reversed = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () ->
                PotShareholderSetBinaryCodec.decode(1, 2, reversed, null));
    }
}
