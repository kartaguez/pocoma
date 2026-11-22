package com.kartaguez.pocoma.domain.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
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
        PotBalanceAggregateBinaryCodec.EncodedAggregate emptyEnc = PotBalanceAggregateBinaryCodec.encode(empty);
        PotBalanceAggregateContent emptyContent = emptyEnc.content();
        assertEquals(1, emptyContent.schemaVersion());
        assertEquals(0, emptyContent.shareholdersCount());
        assertEquals(0, emptyContent.bucketCount());
        assertEquals(8, emptyContent.payload().length);
        assertEquals(empty, PotBalanceAggregateBinaryCodec.decode(
                potId, 1L, emptyEnc.shareholderSet(), emptyContent));

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
        PotBalanceAggregateBinaryCodec.EncodedAggregate encoded = PotBalanceAggregateBinaryCodec.encode(agg);
        PotBalanceAggregateContent content = encoded.content();
        assertEquals(4, content.shareholdersCount());
        assertEquals(2, content.bucketCount());
        assertEquals(encoded.shareholderSet().hash(), content.shareholderSetHash());

        PotBalanceAggregate decoded = PotBalanceAggregateBinaryCodec.decode(
                potId, 12L, encoded.shareholderSet(), content);
        assertEquals(agg, decoded);

        PotBalanceAggregateBinaryCodec.EncodedAggregate reEncoded =
                PotBalanceAggregateBinaryCodec.encodeWithShareholderSet(decoded, encoded.shareholderSet());
        assertSame(encoded.shareholderSet(), reEncoded.shareholderSet());
        assertEquals(content, reEncoded.content());
        assertArrayEquals(content.payload(), reEncoded.content().payload());
        assertEquals(content.hash(), reEncoded.content().hash());
        assertEquals(totalBuckets, decoded.shareholders().stream()
                .map(ShareholderEntry::fixedNet).reduce(Fraction.ZERO, Fraction::add));
    }

    @Test
    void exactContentEqualsAndHashVerificationDetectDivergenceAndDistinctShareholderSets() {
        UUID potId = UUID.randomUUID();
        UUID u0 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID uOther = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotBalanceAggregate agg = new PotBalanceAggregate(
                potId, 1L,
                List.of(new ShareholderEntry(u0, Fraction.ONE, false, Fraction.ZERO)),
                Map.of());
        PotBalanceAggregateBinaryCodec.EncodedAggregate encoded = PotBalanceAggregateBinaryCodec.encode(agg);
        PotBalanceAggregateContent content = encoded.content();

        PotBalanceAggregate aggOtherSet = new PotBalanceAggregate(
                potId, 1L,
                List.of(new ShareholderEntry(uOther, Fraction.ONE, false, Fraction.ZERO)),
                Map.of());
        PotBalanceAggregateBinaryCodec.EncodedAggregate encodedOther = PotBalanceAggregateBinaryCodec.encode(aggOtherSet);
        assertArrayEquals(content.payload(), encodedOther.content().payload());
        assertNotEquals(content.hash(), encodedOther.content().hash());
        assertFalse(content.exactContentEquals(encodedOther.content()));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, encodedOther.shareholderSet(), content));

        byte[] mutated = content.payload();
        mutated[mutated.length - 1] ^= 0x01;
        ContentHash otherSetHash = encodedOther.shareholderSet().hash();
        assertFalse(content.exactContentEquals(1, 1, 0, content.shareholderSetHash(), mutated));
        assertFalse(content.exactContentEquals(1, 1, 0, otherSetHash, content.payload()));
        assertFalse(content.exactContentEquals(1, 1, 1, content.shareholderSetHash(), content.payload()));
        assertFalse(content.exactContentEquals(2, 1, 0, content.shareholderSetHash(), content.payload()));

        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(
                        potId, 1L, encoded.shareholderSet(), 1, 1, 0, mutated, content.hash()));
    }

    @Test
    void strictDecodingRejectsInvalidFlagsIndicesOrderAndConservation() {
        UUID potId = UUID.randomUUID();
        UUID u0 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PotShareholderSet set1 = PotShareholderSetBinaryCodec.encode(List.of(u0));
        PotShareholderSet set2 = PotShareholderSetBinaryCodec.encode(List.of(u0, u1));

        // 1. Invalid deleted flag byte (2)
        byte[] badDeletedFlag = buildRawAggregate(
                List.of(new RawSh(2, Fraction.ONE, Fraction.ZERO)),
                List.of());
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, set1, 1, 1, 0, badDeletedFlag, null));

        // 2. Bucket default index out of bounds (index 2 when shareholdersCount = 2)
        byte[] outOfBoundsIdx = buildRawAggregate(
                List.of(
                        new RawSh(0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(0, 2), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, set2, 1, 2, 1, outOfBoundsIdx, null));

        // 3. Non-ascending default indices ([1, 0])
        byte[] unsortedDefaultIdx = buildRawAggregate(
                List.of(
                        new RawSh(0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(1, 0), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, set2, 1, 2, 1, unsortedDefaultIdx, null));

        // 4. Out-of-order buckets
        byte[] reversedBuckets = buildRawAggregate(
                List.of(
                        new RawSh(0, Fraction.ONE, new Fraction(20, 1)),
                        new RawSh(0, Fraction.ONE, Fraction.ZERO)),
                List.of(
                        new RawBucket(List.of(1), Fraction.ZERO, new Fraction(10, 1), List.of()),
                        new RawBucket(List.of(0), Fraction.ZERO, new Fraction(10, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, set2, 1, 2, 2, reversedBuckets, null));

        // 5. Conservation violation (sum(fixedNet) != sum(bucketAmounts))
        byte[] badConservation = buildRawAggregate(
                List.of(
                        new RawSh(0, Fraction.ONE, new Fraction(10, 1)),
                        new RawSh(0, Fraction.ONE, Fraction.ZERO)),
                List.of(new RawBucket(List.of(0, 1), Fraction.ZERO, new Fraction(9, 1), List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, set2, 1, 2, 1, badConservation, null));

        // 6. Trailing bytes
        PotBalanceAggregateBinaryCodec.EncodedAggregate valid = PotBalanceAggregateBinaryCodec.encode(
                new PotBalanceAggregate(potId, 1L,
                        List.of(new ShareholderEntry(u0, Fraction.ONE, false, Fraction.ZERO)),
                        Map.of()));
        byte[] trailing = Arrays.copyOf(valid.content().payload(), valid.content().payload().length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                PotBalanceAggregateBinaryCodec.decode(potId, 1L, valid.shareholderSet(), 1, 1, 0, trailing, null));
    }

    private record RawSh(int deletedByte, Fraction weight, Fraction fixedNet) {}
    private record RawSpec(int idx, Fraction amount) {}
    private record RawBucket(List<Integer> defaultIndices, Fraction specWeightTotal, Fraction totalAmount, List<RawSpec> specs) {}

    private static byte[] buildRawAggregate(List<RawSh> shareholders, List<RawBucket> buckets) {
        var out = new ByteArrayOutputStream();
        PotBalanceBinaryCodec.writeInt32(out, shareholders.size());
        for (RawSh sh : shareholders) {
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
