package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PotBalanceAggregateBinaryCodecTest {

    @Test
    void roundTripEmptyAndMultiBucketAggregatesWithDeletedAndHugeFractions() {
        UUID potId = UUID.randomUUID();

        // Empty aggregate (0 shareholders, 0 buckets)
        PotBalanceAggregate empty = new PotBalanceAggregate(potId, 1L, List.of(), Map.of());
        PotBalanceAggregateContent emptyContent = PotBalanceAggregateBinaryCodec.encode(empty);
        assertEquals(1, emptyContent.schemaVersion());
        assertEquals(0, emptyContent.shareholdersCount());
        assertEquals(0, emptyContent.bucketCount());
        assertEquals(8, emptyContent.payload().length);
        assertEquals(empty, PotBalanceAggregateBinaryCodec.decode(potId, 1L, emptyContent));

        // Complex multi-bucket aggregate: default bucket + mixed bucket + deleted shareholder + huge fractions
        UUID u0 = new UUID(0x0000000000000001L, 0x0000000000000001L);
        UUID u1 = new UUID(0x7FFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
        UUID u2 = new UUID(0x8000000000000000L, 0x0000000000000000L);
        UUID u3 = new UUID(0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);

        Fraction hugeAmount = new Fraction(BigInteger.TEN.pow(35).add(BigInteger.ONE), BigInteger.valueOf(7));
        Fraction mixedAmount = new Fraction(30, 1);
        Fraction totalBuckets = hugeAmount.add(mixedAmount);

        List<ShareholderEntry> shareholders = List.of(
                new ShareholderEntry(u0, new Fraction(3, 2), false, hugeAmount),
                new ShareholderEntry(u1, Fraction.ONE, false, mixedAmount),
                new ShareholderEntry(u2, new Fraction(5, 3), false, Fraction.ZERO),
                new ShareholderEntry(u3, Fraction.ONE, true, Fraction.ZERO)
        );

        BucketKey defaultBucketKey = new BucketKey(List.of(0, 1, 2), Fraction.ZERO);
        BucketValue defaultBucketVal = new BucketValue(hugeAmount, Map.of());

        BucketKey mixedBucketKey = new BucketKey(List.of(0, 1), new Fraction(4, 1));
        BucketValue mixedBucketVal = new BucketValue(mixedAmount, Map.of(2, new Fraction(120, 1)));

        // Pass buckets in reverse order to confirm canonical encoding order independence
        Map<BucketKey, BucketValue> buckets = new LinkedHashMap<>();
        buckets.put(mixedBucketKey, mixedBucketVal);
        buckets.put(defaultBucketKey, defaultBucketVal);

        PotBalanceAggregate agg = new PotBalanceAggregate(potId, 12L, shareholders, buckets);
        PotBalanceAggregateContent content = PotBalanceAggregateBinaryCodec.encode(agg);
        assertEquals(4, content.shareholdersCount());
        assertEquals(2, content.bucketCount());

        PotBalanceAggregate decoded = PotBalanceAggregateBinaryCodec.decode(potId, 12L, content);
        assertEquals(agg, decoded);

        PotBalanceAggregateContent reEncoded = PotBalanceAggregateBinaryCodec.encode(decoded);
        assertEquals(content, reEncoded);
        assertArrayEquals(content.payload(), reEncoded.payload());
        assertEquals(content.hash(), reEncoded.hash());
        assertEquals(totalBuckets, decoded.shareholders().stream()
                .map(ShareholderEntry::fixedNet).reduce(Fraction.ZERO, Fraction::add));
    }

    @Test
    void exactContentEqualsAndHashVerificationDetectDivergence() {
        UUID potId = UUID.randomUUID();
        UUID u0 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PotBalanceAggregate agg = new PotBalanceAggregate(
                potId, 1L,
                List.of(new ShareholderEntry(u0, Fraction.ONE, false, Fraction.ZERO)),
                Map.of());
        PotBalanceAggregateContent content = PotBalanceAggregateBinaryCodec.encode(agg);

        byte[] mutated = content.payload();
        mutated[mutated.length - 1] ^= 0x01;
        assertFalse(content.exactContentEquals(1, 1, 0, mutated));
        assertFalse(content.exactContentEquals(1, 1, 1, content.payload()));
        assertFalse(content.exactContentEquals(2, 1, 0, content.payload()));

        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 1, 0, mutated, content.hash()));
    }

    @Test
    void strictDecodingRejectsInvalidFlagsIndicesOrderAndConservation() {
        UUID potId = UUID.randomUUID();
        UUID u0 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // 1. Invalid deleted flag byte (2)
        byte[] badDeletedFlag = buildRawAggregate(
                List.of(new RawSh(u0, 2, Fraction.ONE, Fraction.ZERO)),
                List.of());
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 1, 0, badDeletedFlag, null));

        // 2. Reversed shareholder UUID order
        byte[] badShOrder = buildRawAggregate(
                List.of(
                        new RawSh(u1, 0, Fraction.ONE, Fraction.ZERO),
                        new RawSh(u0, 0, Fraction.ONE, Fraction.ZERO)),
                List.of());
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 2, 0, badShOrder, null));

        // 3. Bucket default index out of bounds (index 2 when shareholdersCount = 2)
        byte[] outOfBoundsIdx = buildRawAggregate(
                List.of(
                        new RawSh(u0, 0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(u1, 0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(0, 2), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 2, 1, outOfBoundsIdx, null));

        // 4. Non-ascending default indices ([1, 0])
        byte[] unsortedDefaultIdx = buildRawAggregate(
                List.of(
                        new RawSh(u0, 0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(u1, 0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(1, 0), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 2, 1, unsortedDefaultIdx, null));

        // 5. Out-of-order buckets
        byte[] reversedBuckets = buildRawAggregate(
                List.of(
                        new RawSh(u0, 0, Fraction.ONE, new Fraction(20, 1)),
                        new RawSh(u1, 0, Fraction.ONE, Fraction.ZERO)),
                List.of(
                        new RawBucket(List.of(1), Fraction.ZERO, new Fraction(10, 1), List.of()),
                        new RawBucket(List.of(0), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 2, 2, reversedBuckets, null));

        // 6. Conservation violation (sum(fixedNet) != sum(bucketAmounts))
        byte[] badConservation = buildRawAggregate(
                List.of(
                        new RawSh(u0, 0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(u1, 0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(0, 1), Fraction.ZERO, new Fraction(9, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 2, 1, badConservation, null));

        // 7. Trailing bytes
        PotBalanceAggregateContent valid = PotBalanceAggregateBinaryCodec.encode(
                new PotBalanceAggregate(potId, 1L,
                        List.of(new ShareholderEntry(u0, Fraction.ONE, false, Fraction.ZERO)),
                        Map.of()));
        byte[] trailing = Arrays.copyOf(valid.payload(), valid.payload().length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, 1, 1, 0, trailing, null));
    }

    private record RawSh(UUID id, int deletedByte, Fraction weight, Fraction fixedNet) {}
    private record RawSpec(int idx, Fraction amount) {}
    private record RawBucket(List<Integer> defaultIndices, Fraction specWeightTotal, Fraction totalAmount, List<RawSpec> specs) {}

    private static byte[] buildRawAggregate(List<RawSh> shareholders, List<RawBucket> buckets) {
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, shareholders.size());
        for (RawSh sh : shareholders) {
            PotBalanceBinaryCodec.writeUuid(out, sh.id);
            out.write(sh.deletedByte);
            PotBalanceBinaryCodec.encodeFraction(out, sh.weight);
            PotBalanceBinaryCodec.encodeFraction(out, sh.fixedNet);
        }
        PotBalanceBinaryCodec.writeInt32(out, buckets.size());
        for (RawBucket b : buckets) {
            PotBalanceBinaryCodec.writeInt32(out, b.defaultIndices.size());
            for (int idx : b.defaultIndices) {
                PotBalanceBinaryCodec.writeInt32(out, idx);
            }
            PotBalanceBinaryCodec.encodeFraction(out, b.specWeightTotal);
            PotBalanceBinaryCodec.encodeFraction(out, b.totalAmount);
            PotBalanceBinaryCodec.writeInt32(out, b.specs.size());
            for (RawSpec s : b.specs) {
                PotBalanceBinaryCodec.writeInt32(out, s.idx);
                PotBalanceBinaryCodec.encodeFraction(out, s.amount);
            }
        }
        return out.toByteArray();
    }
}
