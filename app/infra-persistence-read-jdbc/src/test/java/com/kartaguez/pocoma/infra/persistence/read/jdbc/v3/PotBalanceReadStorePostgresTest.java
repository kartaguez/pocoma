package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
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
        var f=new ReadStoreFixture(PG,"4"); var s=new JdbcPotBalanceStore(f.source,f.tx); var p=UUID.randomUUID();
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
        var f=new ReadStoreFixture(PG,"4"); var s=new JdbcPotBalanceStore(f.source,f.tx); var p=UUID.randomUUID(); var v=value(p,1,new Fraction(17,19));
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
        var f=new ReadStoreFixture(PG,"3"); var p=UUID.randomUUID(); var pv=new JdbcPotVersionStore(f.source,f.tx);
        var old=new PotVersion(p,1,1,1,false,List.of(),List.of(),Set.of(a),Set.of()); f.write(() -> pv.insertAndAdvance(old));
        assertEquals(1,f.migrate("4")); assertEquals(old,f.tx.read(c -> pv.read(p,1,c).orElseThrow()));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_pot_balance','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        var s=new JdbcPotBalanceStore(f.source,f.tx); var unrelated=UUID.randomUUID(); f.write(() -> s.insertOrVerify(value(unrelated,1,Fraction.ONE)));
        assertThrows(RuntimeException.class,() -> f.oracle.execute("truncate read_pot_balance"));
        f.evidence("migration no interparticipant FK",f.oracle.queryForList("select * from read_pot_balance"));
    }
}
