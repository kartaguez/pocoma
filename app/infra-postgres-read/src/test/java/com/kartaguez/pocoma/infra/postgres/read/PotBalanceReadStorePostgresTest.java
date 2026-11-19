package com.kartaguez.pocoma.infra.postgres.read;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;
@Testcontainers
class PotBalanceReadStorePostgresTest {
    @Container static final PostgreSQLContainer PG=ReadStoreFixture.container();
    final UUID a=new UUID(1,1),b=new UUID(1,2),z=new UUID(1,3);
    PotBalance value(UUID p,long v,Fraction n) { return new PotBalance(p,v,Map.of(a,n,b,Fraction.ZERO.subtract(n),z,Fraction.ZERO)); }
    @Test void historyAndRationals() throws Exception {
        var f=new ReadStoreFixture(PG); var s=new JdbcPotBalanceStore(f.source,f.tx); var p=UUID.randomUUID();
        var n=new Fraction(BigInteger.TEN.pow(40).add(BigInteger.valueOf(7)),BigInteger.valueOf(97));
        for(long v:new long[]{3,1,2,3}) f.write(() -> { s.insertOrVerify(value(p,v,n)); s.requireStored(p,v,Optional.of(value(p,v,n))); });
        for(long v:new long[]{1,2,3}) assertEquals(value(p,v,n),f.tx.read(c -> s.read(p,v,c).orElseThrow()));
        var q=UUID.randomUUID(); f.write(() -> s.insertOrVerify(value(q,1,new Fraction(3,5))));
        assertEquals(value(q,1,new Fraction(3,5)),f.tx.read(c -> s.read(q,1,c).orElseThrow()),"P02-L separate Pot keys and content");
        assertEquals(value(p,1,n),f.tx.read(c -> s.read(p,1,c).orElseThrow()));
        f.write(() -> s.requireStored(p,4,Optional.empty())); assertTrue(f.tx.read(c -> s.read(p,4,c)).isEmpty(),"D has no balance");
        f.write(() -> s.insertOrVerify(value(p,2,n))); assertEquals(Set.of(a,b,z),f.tx.read(c -> s.read(p,2,c).orElseThrow().values().keySet()));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.insertOrVerify(value(p,2,Fraction.ONE))));
        f.evidence("P02 history exact rationals zero terminal",f.tx.read(c -> s.read(p,2,c)));
    }
    @Test void concurrentImmutablePublication() throws Exception {
        var f=new ReadStoreFixture(PG); var s=new JdbcPotBalanceStore(f.source,f.tx); var p=UUID.randomUUID(); var v=value(p,1,new Fraction(17,19));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var x=pool.submit(() -> { start.await(); f.write(() -> s.insertOrVerify(v)); return true; });
            var y=pool.submit(() -> { start.await(); f.write(() -> s.insertOrVerify(v)); return true; });
            start.countDown(); assertTrue(x.get(15,TimeUnit.SECONDS)); assertTrue(y.get(15,TimeUnit.SECONDS));
        }
        assertEquals(v,f.tx.read(c -> s.read(p,1,c).orElseThrow()));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.insertOrVerify(value(p,1,Fraction.ONE))));
        f.evidence("P02-I concurrent immutable",v.values());
    }
    @Test void migrationAndPrivileges() throws Exception {
        var f=ReadStoreFixture.historical(PG,"3"); var p=UUID.randomUUID();
        String encoded="{\"deleted\":false,\"detailsVersion\":1,\"headerVersion\":1,\"shareholders\":[],\"expenses\":[],\"authorizedUsers\":[\""+a+"\"],\"attachedActiveUsers\":[]}";
        f.write(() -> {
            f.sql.update("insert into read_pot_version(pot_id,pot_version,schema_version,payload) values (?,1,1,?::jsonb)",p,encoded);
            f.sql.update("insert into read_current_pot(pot_id,pot_version) values (?,1)",p);
        });
        assertEquals(1,f.migrate("4"));
        assertEquals(Boolean.FALSE,f.sql.queryForObject("select (payload->>'deleted')::boolean from read_pot_version where pot_id=? and pot_version=1",Boolean.class,p));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        var s=new JdbcPotBalanceStore(f.source,f.tx); var unrelated=UUID.randomUUID(); f.write(() -> s.insertOrVerify(value(unrelated,1,Fraction.ONE)));
        assertThrows(RuntimeException.class,() -> f.oracle.execute("truncate read_pot_balance"));
        f.evidence("migration no interparticipant FK",f.oracle.queryForList("select * from read_pot_balance"));
    }

    @Test void aggregatePersistenceLookupConstraintsImmutabilityAndV1ToV2Upgrade() throws Exception {
        // 1. Fresh V1+V2 migration: FK, privileges, triggers, lookup bounds, roundtrip of mixed buckets & large rationals
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
        assertFalse(
                f.oracle.queryForObject(
                        "select has_table_privilege(?,'read_pot_balance_aggregate','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                        Boolean.class,
                        f.runtimeRole));
        assertTrue(
                f.oracle.queryForObject(
                        "select has_table_privilege(?,'read_pot_balance_aggregate','SELECT')",
                        Boolean.class,
                        f.runtimeRole));
        assertTrue(
                f.oracle.queryForObject(
                        "select has_table_privilege(?,'read_pot_balance_aggregate','INSERT')",
                        Boolean.class,
                        f.runtimeRole));
        assertThrows(
                RuntimeException.class,
                () -> f.oracle.update("update read_pot_balance_aggregate set bucket_count=99 where pot_id=?", p));
        assertThrows(
                RuntimeException.class,
                () -> f.oracle.update("delete from read_pot_balance_aggregate where pot_id=?", p));
        assertThrows(
                RuntimeException.class,
                () -> f.oracle.execute("truncate read_pot_balance_aggregate"));

        // Corrupt JSONB codec rejection
        assertThrows(
                IllegalStateException.class,
                () -> PotBalanceAggregateJson.decode(p, 1L, 1, "{\"s\":[],\"b\":[{\"p\":[0],\"swn\":\"0\",\"swd\":\"1\",\"an\":\"1\",\"ad\":\"1\",\"sq\":[]}]}"));
        assertThrows(
                IllegalStateException.class,
                () -> PotBalanceAggregateJson.decode(p, 1L, 2, "{\"s\":[],\"b\":[]}"));
        assertThrows(
                IllegalStateException.class,
                () -> PotBalanceAggregateJson.decode(p, 1L, 1, "{\"s\":[{\"id\":\"" + a + "\",\"wn\":\"2\",\"wd\":\"2\",\"del\":false,\"fn\":\"0\",\"fd\":\"1\"}],\"b\":[]}"));

        // 2. Additive V1 -> V2 upgrade with existing read_pot_balance rows
        var upgrade = ReadStoreFixture.atVersion(PG, "1");
        var upgradeStore = new JdbcPotBalanceStore(upgrade.source, upgrade.tx);
        var existingBal = value(p, 1L, new Fraction(7, 3));
        upgrade.write(() -> upgradeStore.insertOrVerify(existingBal));
        assertEquals(1, upgrade.migrate("2"));
        assertEquals(0, upgrade.migrate("2"));
        assertEquals(existingBal, upgrade.tx.read(c -> upgradeStore.read(p, 1L, c).orElseThrow()));
        assertTrue(upgrade.tx.read(c -> upgradeStore.readLatestAggregateBefore(p, 2L, 1L, c)).isEmpty());
        upgrade.write(() -> {
            upgradeStore.insertOrVerify(bal5);
            upgradeStore.insertOrVerifyAggregate(agg5);
        });
        assertEquals(Optional.of(agg5), upgrade.tx.read(c -> upgradeStore.readLatestAggregateBefore(p, 6L, 1L, c)));
    }
}
