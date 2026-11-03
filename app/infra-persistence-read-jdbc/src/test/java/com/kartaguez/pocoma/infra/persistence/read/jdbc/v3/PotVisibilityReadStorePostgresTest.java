package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.projection.v3.PotVisibility;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class PotVisibilityReadStorePostgresTest {
    @Container static final PostgreSQLContainer PG=ReadStoreFixture.container();
    @Test void historyAndKeyset() throws Exception {
        var f=new ReadStoreFixture(PG,"5"); var s=new JdbcPotVisibilityStore(f.source,f.tx); var p=new UUID(2,1); var u=UUID.randomUUID(); var c=UUID.randomUUID();
        for(var v:List.of(new PotVisibility(p,1,Set.of(c,u)),new PotVisibility(p,2,Set.of(c)),new PotVisibility(p,3,Set.of(c,u)),new PotVisibility(p,4,Set.of()),new PotVisibility(p,2,Set.of(c))))
            f.write(() -> s.requireStored(s.apply(v)));
        assertEquals(new PotVisibility(p,4,Set.of()),f.tx.read(x -> s.read(p,x).orElseThrow()));
        assertEquals(2,f.oracle.queryForObject("select count(*) from read_user_pot where pot_id=? and not visible",Integer.class,p));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.apply(new PotVisibility(p,4,Set.of(u)))));
        for(long n:new long[]{3,1,2}) f.write(() -> s.requireStored(s.apply(new PotVisibility(new UUID(3,n),1,Set.of(u)))));
        assertEquals(List.of(new UUID(3,1),new UUID(3,2)),f.tx.read(x -> s.visiblePots(u,null,2,x)));
        assertEquals(List.of(new UUID(3,3)),f.tx.read(x -> s.visiblePots(u,new UUID(3,2),2,x)));
        f.evidence("P03-C/E/F/H/K keyset tombstones",f.oracle.queryForList("select * from read_user_pot order by pot_id,user_id"));
    }
    @Test void concurrentGuardAndAtomicReaders() throws Exception {
        var f=new ReadStoreFixture(PG,"5"); var s=new JdbcPotVisibilityStore(f.source,f.tx); var p=UUID.randomUUID(); var a=UUID.randomUUID(); var b=UUID.randomUUID();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            var first=pool.submit(() -> { f.write(() -> { s.requireStored(s.apply(new PotVisibility(p,1,Set.of(a)))); entered.countDown(); try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier"); } catch(InterruptedException e) { throw new RuntimeException(e); } }); return true; });
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(0,f.oracle.queryForObject("select count(*) from read_user_pot where pot_id=?",Integer.class,p),"uncommitted set invisible");
            var second=pool.submit(() -> { f.write(() -> s.requireStored(s.apply(new PotVisibility(p,2,Set.of(b))))); return true; });
            try {
                f.awaitLock("read_pot_visibility_state");
                var q=UUID.randomUUID(); f.write(() -> s.requireStored(s.apply(new PotVisibility(q,1,Set.of(a)))));
            } finally { release.countDown(); }
            assertTrue(first.get(15,TimeUnit.SECONDS)); assertTrue(second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(new PotVisibility(p,2,Set.of(b)),f.tx.read(x -> s.read(p,x).orElseThrow()));
        assertFalse(f.oracle.queryForObject("select visible from read_user_pot where pot_id=? and user_id=?",Boolean.class,p,a));
        f.evidence("P03-A/B/G/L/N",f.tx.read(x -> s.read(p,x)));
    }
    @Test void plansAndProfiles() throws Exception {
        var f=new ReadStoreFixture(PG,"5"); var s=new JdbcPotVisibilityStore(f.source,f.tx); var p=UUID.randomUUID(); var a=UUID.randomUUID();
        f.write(() -> s.apply(new PotVisibility(p,1,Set.of(a))));
        f.oracle.update("insert into read_user_pot select md5(n::text)::uuid,?,1,false from generate_series(1,10000) n on conflict do nothing",p);
        f.oracle.execute("analyze read_user_pot");
        var plan=f.oracle.queryForList("explain (analyze,buffers,format text) select user_id,pot_version from read_user_pot where pot_id='"+p+"' and visible=true");
        assertTrue(plan.toString().contains("read_visible_by_") && !plan.toString().contains("Seq Scan"),plan.toString());
        f.write(() -> s.requireStored(s.apply(new PotVisibility(p,2,Set.of()))));
        assertEquals(10001,f.oracle.queryForObject("select count(*) from read_user_pot where pot_id=?",Integer.class,p));
        f.evidence("P03-I/O visible-only physical plan",plan);
    }
    @Test void migrationAndPrivileges() throws Exception {
        var f=new ReadStoreFixture(PG,"4"); assertEquals(1,f.migrate("5")); var s=new JdbcPotVisibilityStore(f.source,f.tx); var p=UUID.randomUUID();
        f.write(() -> s.requireStored(s.apply(new PotVisibility(p,1,Set.of(UUID.randomUUID())))));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_user_pot','DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertEquals(2,f.oracle.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname in ('read_visible_by_pot','read_visible_by_user')",Integer.class));
        f.evidence("migration Visibility",f.oracle.queryForList("select * from read_pot_visibility_state"));
    }
}
