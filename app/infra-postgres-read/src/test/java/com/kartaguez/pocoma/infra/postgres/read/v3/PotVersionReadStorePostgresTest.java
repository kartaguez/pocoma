package com.kartaguez.pocoma.infra.postgres.read.v3;
import com.kartaguez.pocoma.domain.projection.v3.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class PotVersionReadStorePostgresTest {
    @Container static final PostgreSQLContainer PG=ReadStoreFixture.container();
    PotVersion value(UUID p,long v,boolean deleted) { return new PotVersion(p,v,v,1,deleted,List.of(),List.of(),Set.of(new UUID(1,1)),Set.of()); }
    @Test void historyAndReplay() throws Exception {
        var f=new ReadStoreFixture(PG); var s=new JdbcPotVersionStore(f.source,f.tx); var p=UUID.randomUUID(); var q=UUID.randomUUID();
        for(long v:new long[]{3,1,2,3}) f.write(() -> { s.insertAndAdvance(value(p,v,v==3)); s.requireStored(value(p,v,v==3)); });
        f.write(() -> s.insertAndAdvance(value(q,1,false)));
        assertEquals(3L,f.oracle.queryForObject("select pot_version from read_current_pot where pot_id=?",Long.class,p));
        for(long v:new long[]{1,2,3}) assertEquals(value(p,v,v==3),f.tx.read(c -> s.read(p,v,c).orElseThrow()));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.insertAndAdvance(value(p,2,true))));
        assertEquals(value(p,2,false),f.tx.read(c -> s.read(p,2,c).orElseThrow())); f.evidence("P01-A/B/C/D/J/K",f.oracle.queryForList("select * from read_current_pot"));
    }
    @Test void concurrentPublishers() throws Exception {
        var f=new ReadStoreFixture(PG); var s=new JdbcPotVersionStore(f.source,f.tx); var p=UUID.randomUUID();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(() -> { start.await(); f.write(() -> s.insertAndAdvance(value(p,1,false))); return true; });
            var b=pool.submit(() -> { start.await(); f.write(() -> s.insertAndAdvance(value(p,2,false))); return true; });
            start.countDown(); assertTrue(a.get(15,TimeUnit.SECONDS)); assertTrue(b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2L,f.oracle.queryForObject("select pot_version from read_current_pot where pot_id=?",Long.class,p));
        f.write(() -> s.insertAndAdvance(value(p,2,false)));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.insertAndAdvance(value(p,2,true)))); f.evidence("P01-E/F immutable divergence",f.tx.read(c -> s.read(p,2,c)));
        var q=UUID.randomUUID();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            var winner=pool.submit(() -> { f.write(() -> { s.insertAndAdvance(value(q,1,false)); entered.countDown(); try { if(!release.await(12,TimeUnit.SECONDS)) throw new IllegalStateException("release barrier"); } catch(InterruptedException ex) { throw new RuntimeException(ex); } }); return true; });
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var loser=pool.submit(() -> { try { f.write(() -> s.insertAndAdvance(value(q,1,true))); return false; } catch(RuntimeException divergence) { return true; } });
            try { f.awaitLock("read_pot_version"); } finally { release.countDown(); }
            assertTrue(winner.get(15,TimeUnit.SECONDS)); assertTrue(loser.get(15,TimeUnit.SECONDS));
        }
        assertEquals(value(q,1,false),f.tx.read(c -> s.read(q,1,c).orElseThrow()),"fresh RC read compares the concurrent committed winner");
    }
    @Test void migrationAndPrivileges() throws Exception {
        var f=new ReadStoreFixture(PG); assertEquals(0,f.migrate("3"));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?, 'read_pot_version','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertThrows(RuntimeException.class,() -> f.oracle.update("insert into read_current_pot values (?,1)",UUID.randomUUID()));
        var p=UUID.randomUUID(); var s=new JdbcPotVersionStore(f.source,f.tx); f.write(() -> s.insertAndAdvance(value(p,1,false)));
        assertThrows(RuntimeException.class,() -> f.oracle.update("delete from read_pot_version where pot_id=?",p));
        assertThrows(RuntimeException.class,() -> s.insertAndAdvance(value(p,2,false)));
        f.evidence("migration privileges FK owner",f.oracle.queryForList("select version from flyway_schema_history where success"));
    }
}
