package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class CurrentBindingReadStorePostgresTest {
    @Container static final PostgreSQLContainer PG=ReadStoreFixture.container();
    final ExternalIdentity e=new ExternalIdentity("issuer","subject"); final ExternalUserIdentityId id=new ExternalUserIdentityId(UUID.randomUUID()); final PocomaUserId user=new PocomaUserId(UUID.randomUUID());
    ExternalUserIdentity v(long n,boolean active) { return new ExternalUserIdentity(id,e,n,active?user:null,active?new BindingToken("private-"+n):null); }
    @Test void historyAndIsolation() throws Exception {
        var f=new ReadStoreFixture(PG,"6"); var s=new JdbcCurrentBindingStore(f.source,f.tx);
        for(var value:List.of(v(1,true),v(2,false),v(3,true),v(1,true),v(2,false))) f.write(() -> { s.apply(value); s.requireStored(value); });
        assertEquals(v(3,true),f.tx.read(c -> s.read(e,c).orElseThrow()));
        assertTrue(f.tx.read(c -> s.read(new ExternalIdentity("other","subject"),c)).isEmpty());
        assertTrue(f.tx.read(c -> s.read(new ExternalIdentity("issuer","other"),c)).isEmpty());
        assertFalse(v(3,true).toString().contains("private-3")); f.evidence("EUI out of order and relink",v(3,true));
    }
    @Test void concurrentVersionsAndDivergence() throws Exception {
        var f=new ReadStoreFixture(PG,"6"); var s=new JdbcCurrentBindingStore(f.source,f.tx);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(() -> { start.await(); f.write(() -> s.apply(v(1,true))); return true; });
            var b=pool.submit(() -> { start.await(); f.write(() -> s.apply(v(2,false))); return true; });
            start.countDown(); assertTrue(a.get(15,TimeUnit.SECONDS)); assertTrue(b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(v(2,false),f.tx.read(c -> s.read(e,c).orElseThrow()));
        assertThrows(RuntimeException.class,() -> f.write(() -> s.apply(v(2,true))));
        assertEquals(v(2,false),f.tx.read(c -> s.read(e,c).orElseThrow())); f.evidence("EUI same version divergence",v(2,false));
    }
    @Test void migrationAndPrivileges() throws Exception {
        var f=new ReadStoreFixture(PG,"5"); assertEquals(1,f.migrate("6"));
        var s=new JdbcCurrentBindingStore(f.source,f.tx); f.write(() -> s.apply(v(1,true)));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_current_binding','DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertThrows(RuntimeException.class,() -> f.oracle.update("insert into read_current_binding values ('x','y',?,1,?,null)",UUID.randomUUID(),UUID.randomUUID()));
        assertEquals(0,f.oracle.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and tablename='read_current_binding' and indexdef like '%(user_id)%'",Integer.class));
        f.evidence("migration EUI shape and no inverse index",v(1,true));
    }
}
