package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregateCalculator;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PotBalanceReadStorePostgresTest {
    @Container
    static final PostgreSQLContainer PG = ReadStoreFixture.container();
    final UUID a = new UUID(1, 1), b = new UUID(1, 2), z = new UUID(1, 3);

    PotBalance value(UUID p, long v, Fraction n) {
        return new PotBalance(p, v, Map.of(a, n, b, Fraction.ZERO.subtract(n), z, Fraction.ZERO));
    }

    @Test
    void historyAndRationals() throws Exception {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotBalanceStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var n = new Fraction(BigInteger.TEN.pow(40).add(BigInteger.valueOf(7)), BigInteger.valueOf(97));
        for (long v : new long[]{3, 1, 2, 3}) {
            f.write(() -> {
                s.insertOrVerify(value(p, v, n));
                s.requireStored(p, v, Optional.of(value(p, v, n)));
            });
        }
        // All 3 versions share the exact same single physical balance content row
        assertEquals(3, f.sql.queryForObject("select count(*) from read_pot_balance where pot_id=?", Integer.class, p));
        assertEquals(1, f.sql.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
        for (long v : new long[]{1, 2, 3}) {
            assertEquals(value(p, v, n), f.tx.read(c -> s.read(p, v, c).orElseThrow()));
        }
        var q = UUID.randomUUID();
        f.write(() -> s.insertOrVerify(value(q, 1, new Fraction(3, 5))));
        assertEquals(value(q, 1, new Fraction(3, 5)), f.tx.read(c -> s.read(q, 1, c).orElseThrow()), "P02-L separate Pot keys and content");
        assertEquals(value(p, 1, n), f.tx.read(c -> s.read(p, 1, c).orElseThrow()));
        assertEquals(2, f.sql.queryForObject("select count(*) from read_pot_balance_content", Integer.class));

        f.write(() -> s.requireStored(p, 4, Optional.empty()));
        assertTrue(f.tx.read(c -> s.read(p, 4, c)).isEmpty(), "D has no balance");
        assertEquals(ReadEvidence.SATISFIED, f.tx.read(c -> s.evidence(p, 2, Optional.of(value(p, 2, n)), c)));
        assertEquals(ReadEvidence.MISSING, f.tx.read(c -> s.evidence(p, 4, Optional.of(value(p, 4, n)), c)));
        assertEquals(ReadEvidence.SATISFIED, f.tx.read(c -> s.evidence(p, 4, Optional.empty(), c)));
        assertEquals(ReadEvidence.DIVERGENT, f.tx.read(c -> s.evidence(p, 2, Optional.of(value(p, 2, Fraction.ONE)), c)));

        f.write(() -> s.insertOrVerify(value(p, 2, n)));
        assertEquals(Set.of(a, b, z), f.tx.read(c -> s.read(p, 2, c).orElseThrow().values().keySet()));
        assertThrows(RuntimeException.class, () -> f.write(() -> s.insertOrVerify(value(p, 2, Fraction.ONE))));
        f.evidence("P02 history exact rationals zero terminal", f.tx.read(c -> s.read(p, 2, c)));
    }

    @Test
    void concurrentImmutablePublication() throws Exception {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotBalanceStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var v1 = value(p, 1, new Fraction(17, 19));
        var v2 = value(p, 2, new Fraction(17, 19)); // same content, different version
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var x = pool.submit(() -> { start.await(); f.write(() -> s.insertOrVerify(v1)); return true; });
            var y = pool.submit(() -> { start.await(); f.write(() -> s.insertOrVerify(v1)); return true; });
            var w = pool.submit(() -> { start.await(); f.write(() -> s.insertOrVerify(v2)); return true; });
            start.countDown();
            assertTrue(x.get(15, TimeUnit.SECONDS));
            assertTrue(y.get(15, TimeUnit.SECONDS));
            assertTrue(w.get(15, TimeUnit.SECONDS));
        }
        assertEquals(v1, f.tx.read(c -> s.read(p, 1, c).orElseThrow()));
        assertEquals(v2, f.tx.read(c -> s.read(p, 2, c).orElseThrow()));
        assertEquals(1, f.sql.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
        assertThrows(RuntimeException.class, () -> f.write(() -> s.insertOrVerify(value(p, 1, Fraction.ONE))));
        f.evidence("P02-I concurrent immutable", v1.values());
    }

    @Test
    void independentDeduplicationSituationsACDAndHashCollisionRollback() {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotBalanceStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var calc = new PotBalanceAggregateCalculator();

        // v1: initial state
        var agg1 = new PotBalanceAggregate(
                p, 1L,
                List.of(
                        new PotBalanceAggregate.ShareholderEntry(a, Fraction.ONE, false, new Fraction(10, 1)),
                        new PotBalanceAggregate.ShareholderEntry(b, Fraction.ONE, false, Fraction.ZERO),
                        new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, false, Fraction.ZERO)),
                Map.of(
                        new PotBalanceAggregate.BucketKey(List.of(0, 1), Fraction.ZERO),
                        new PotBalanceAggregate.BucketValue(new Fraction(10, 1), Map.of())));
        var bal1 = calc.calculate(agg1);

        // Situation A (v2): identical balance & identical aggregate
        var agg2 = new PotBalanceAggregate(p, 2L, agg1.shareholders(), agg1.buckets());
        var bal2 = calc.calculate(agg2);

        // Situation C (v3): identical balance, different aggregate (z logically deleted, or homothetic weights)
        var agg3 = new PotBalanceAggregate(
                p, 3L,
                List.of(
                        new PotBalanceAggregate.ShareholderEntry(a, new Fraction(2, 1), false, new Fraction(10, 1)),
                        new PotBalanceAggregate.ShareholderEntry(b, new Fraction(2, 1), false, Fraction.ZERO),
                        new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, true, Fraction.ZERO)),
                agg1.buckets());
        var bal3 = calc.calculate(agg3);
        assertEquals(bal1.values(), bal3.values());

        // Situation D (v4): both balance and aggregate changed
        var agg4 = new PotBalanceAggregate(
                p, 4L,
                List.of(
                        new PotBalanceAggregate.ShareholderEntry(a, new Fraction(3, 1), false, new Fraction(10, 1)),
                        new PotBalanceAggregate.ShareholderEntry(b, Fraction.ONE, false, Fraction.ZERO),
                        new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, true, Fraction.ZERO)),
                agg1.buckets());
        var bal4 = calc.calculate(agg4);
        assertNotEquals(bal1.values(), bal4.values());

        f.write(() -> { s.insertOrVerify(bal1); s.insertOrVerifyAggregate(agg1); });
        f.write(() -> { s.insertOrVerify(bal2); s.insertOrVerifyAggregate(agg2); });
        f.write(() -> { s.insertOrVerify(bal3); s.insertOrVerifyAggregate(agg3); });
        f.write(() -> { s.insertOrVerify(bal4); s.insertOrVerifyAggregate(agg4); });

        // 4 logical versions, 2 distinct balances (v1/v2/v3 shared, v4 new), 3 distinct aggregates (v1/v2 shared, v3 new, v4 new)
        assertEquals(4, f.sql.queryForObject("select count(*) from read_pot_balance where pot_id=?", Integer.class, p));
        assertEquals(2, f.sql.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
        assertEquals(4, f.sql.queryForObject("select count(*) from read_pot_balance_aggregate where pot_id=?", Integer.class, p));
        assertEquals(3, f.sql.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

        // Direct historical reads
        assertEquals(bal1, f.tx.read(c -> s.read(p, 1L, c).orElseThrow()));
        assertEquals(bal2, f.tx.read(c -> s.read(p, 2L, c).orElseThrow()));
        assertEquals(bal3, f.tx.read(c -> s.read(p, 3L, c).orElseThrow()));
        assertEquals(bal4, f.tx.read(c -> s.read(p, 4L, c).orElseThrow()));

        // Simulated hash collision on balance content: pre-insert divergent payload under target hash -> IllegalStateException & rollback
        var bal5 = value(p, 5L, new Fraction(77, 13));
        var bal5Content = PotBalanceBinaryCodec.encode(bal5);
        byte[] corruptedPayload = bal5Content.payload();
        corruptedPayload[corruptedPayload.length - 1] ^= 0x01;
        f.oracle.update(
                "insert into read_pot_balance_content(content_hash, schema_version, shareholders_count, payload) values (?, 1, ?, ?)",
                bal5Content.hash().bytes(), bal5Content.shareholdersCount(), corruptedPayload);
        var balCollision = assertThrows(RuntimeException.class, () -> f.write(() -> s.insertOrVerify(bal5)));
        assertTrue(hasCause(balCollision, IllegalStateException.class, "Divergent read_pot_balance_content"));
        assertEquals(0, f.sql.queryForObject("select count(*) from read_pot_balance where pot_id=? and pot_version=5", Integer.class, p));

        // Simulated hash collision on aggregate content -> IllegalStateException & rollback of whole tx (including balance v6!)
        var agg6 = new PotBalanceAggregate(
                p, 6L,
                List.of(
                        new PotBalanceAggregate.ShareholderEntry(a, new Fraction(5, 1), false, new Fraction(20, 1)),
                        new PotBalanceAggregate.ShareholderEntry(b, Fraction.ONE, false, Fraction.ZERO),
                        new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, true, Fraction.ZERO)),
                Map.of(
                        new PotBalanceAggregate.BucketKey(List.of(0, 1), Fraction.ZERO),
                        new PotBalanceAggregate.BucketValue(new Fraction(20, 1), Map.of())));
        var bal6 = calc.calculate(agg6);
        var agg6Content = PotBalanceAggregateBinaryCodec.encode(agg6);
        byte[] corruptedAggPayload = agg6Content.payload();
        corruptedAggPayload[corruptedAggPayload.length - 1] ^= 0x01;
        f.oracle.update(
                "insert into read_pot_balance_aggregate_content(content_hash, schema_version, shareholders_count, bucket_count, payload) values (?, 1, ?, ?, ?)",
                agg6Content.hash().bytes(), agg6Content.shareholdersCount(), agg6Content.bucketCount(), corruptedAggPayload);
        var aggCollision = assertThrows(RuntimeException.class, () -> f.write(() -> {
            s.insertOrVerify(bal6);
            s.insertOrVerifyAggregate(agg6);
        }));
        assertTrue(hasCause(aggCollision, IllegalStateException.class, "Divergent read_pot_balance_aggregate_content"));
        assertEquals(0, f.sql.queryForObject("select count(*) from read_pot_balance where pot_id=? and pot_version=6", Integer.class, p));
        assertEquals(0, f.sql.queryForObject("select count(*) from read_pot_balance_aggregate where pot_id=? and pot_version=6", Integer.class, p));
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type, String messagePart) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur) && cur.getMessage() != null && cur.getMessage().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void migrationAndPrivileges() throws Exception {
        var f = new ReadStoreFixture(PG);
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance','UPDATE,DELETE,TRUNCATE')", Boolean.class, f.runtimeRole));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance_content','UPDATE,DELETE,TRUNCATE')", Boolean.class, f.runtimeRole));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance_aggregate','UPDATE,DELETE,TRUNCATE')", Boolean.class, f.runtimeRole));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance_aggregate_content','UPDATE,DELETE,TRUNCATE')", Boolean.class, f.runtimeRole));
        var s = new JdbcPotBalanceStore(f.source, f.tx);
        var unrelated = UUID.randomUUID();
        // No interparticipant FK to read_pot_version
        f.write(() -> s.insertOrVerify(value(unrelated, 1, Fraction.ONE)));
        assertThrows(RuntimeException.class, () -> f.oracle.execute("truncate read_pot_balance cascade"));
        assertThrows(RuntimeException.class, () -> f.oracle.execute("truncate read_pot_balance_content cascade"));
        f.evidence("migration no interparticipant FK", f.oracle.queryForList("select pot_id, pot_version from read_pot_balance"));
    }

    @Test
    void aggregatePersistenceLookupConstraintsAndImmutability() {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotBalanceStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var other = UUID.randomUUID();
        var largeWeight = new Fraction(BigInteger.TEN.pow(30).add(BigInteger.valueOf(3)), BigInteger.valueOf(7));
        var specTotal = new Fraction(5, 2);
        var bucketAmount = new Fraction(100, 1);
        var agg1 =
                new PotBalanceAggregate(
                        p,
                        1L,
                        List.of(
                                new PotBalanceAggregate.ShareholderEntry(a, largeWeight, false, new Fraction(100, 1)),
                                new PotBalanceAggregate.ShareholderEntry(b, Fraction.ONE, false, Fraction.ZERO),
                                new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, true, Fraction.ZERO)),
                        Map.of(
                                new PotBalanceAggregate.BucketKey(List.of(0), specTotal),
                                new PotBalanceAggregate.BucketValue(
                                        bucketAmount, Map.of(1, bucketAmount.multiply(specTotal)))));
        var agg5 = new PotBalanceAggregate(p, 5L, agg1.shareholders(), agg1.buckets());
        var aggOther = new PotBalanceAggregate(other, 4L, agg1.shareholders(), agg1.buckets());

        // FK: inserting aggregate without matching read_pot_balance must fail
        assertThrows(RuntimeException.class, () -> f.write(() -> s.insertOrVerifyAggregate(agg1)));

        // Insert balance + aggregate atomically
        var calc = new PotBalanceAggregateCalculator();
        var bal1 = calc.calculate(agg1);
        var bal5 = calc.calculate(agg5);
        var balOther = calc.calculate(aggOther);
        f.write(() -> {
            s.insertOrVerify(bal1);
            s.insertOrVerifyAggregate(agg1);
            s.requireAggregateStored(agg1);
        });
        f.write(() -> {
            s.insertOrVerify(bal5);
            s.insertOrVerifyAggregate(agg5);
        });
        f.write(() -> {
            s.insertOrVerify(balOther);
            s.insertOrVerifyAggregate(aggOther);
        });

        // All 3 versions share 1 physical balance content and 1 physical aggregate content
        assertEquals(1, f.sql.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
        assertEquals(1, f.sql.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

        // Idempotent replay succeeds; divergent replay fails
        f.write(() -> s.insertOrVerifyAggregate(agg1));
        var divergent =
                new PotBalanceAggregate(
                        p,
                        1L,
                        List.of(
                                new PotBalanceAggregate.ShareholderEntry(a, Fraction.ONE, false, Fraction.ZERO),
                                new PotBalanceAggregate.ShareholderEntry(b, Fraction.ONE, false, Fraction.ZERO),
                                new PotBalanceAggregate.ShareholderEntry(z, Fraction.ONE, true, Fraction.ZERO)),
                        Map.of());
        assertThrows(RuntimeException.class, () -> f.write(() -> s.insertOrVerifyAggregate(divergent)));

        // Lookup bounds & pot isolation
        var kFor7 = f.tx.read(c -> s.readLatestAggregateBefore(p, 7L, 1L, c));
        assertEquals(Optional.of(agg5), kFor7);
        assertTrue(f.tx.read(c -> s.readLatestAggregateBefore(p, 7L, 6L, c)).isEmpty());
        var kFor5 = f.tx.read(c -> s.readLatestAggregateBefore(p, 5L, 1L, c));
        assertEquals(Optional.of(agg1), kFor5);
        assertTrue(f.tx.read(c -> s.readLatestAggregateBefore(p, 1L, 1L, c)).isEmpty());

        // Privileges & physical immutability triggers
        for (String table : List.of("read_pot_balance_aggregate", "read_pot_balance_aggregate_content")) {
            assertFalse(
                    f.oracle.queryForObject(
                            "select has_table_privilege(?,?,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                            Boolean.class, f.runtimeRole, table));
            assertTrue(
                    f.oracle.queryForObject(
                            "select has_table_privilege(?,?,'SELECT')",
                            Boolean.class, f.runtimeRole, table));
            assertTrue(
                    f.oracle.queryForObject(
                            "select has_table_privilege(?,?,'INSERT')",
                            Boolean.class, f.runtimeRole, table));
        }
        assertThrows(RuntimeException.class, () -> f.oracle.update("update read_pot_balance_aggregate set pot_version=99 where pot_id=?", p));
        assertThrows(RuntimeException.class, () -> f.oracle.update("delete from read_pot_balance_aggregate where pot_id=?", p));
        assertThrows(RuntimeException.class, () -> f.oracle.execute("truncate read_pot_balance_aggregate"));
        assertThrows(RuntimeException.class, () -> f.oracle.update("update read_pot_balance_aggregate_content set bucket_count=99"));
        assertThrows(RuntimeException.class, () -> f.oracle.update("delete from read_pot_balance_aggregate_content"));
        assertThrows(RuntimeException.class, () -> f.oracle.execute("truncate read_pot_balance_aggregate_content cascade"));
    }
}
