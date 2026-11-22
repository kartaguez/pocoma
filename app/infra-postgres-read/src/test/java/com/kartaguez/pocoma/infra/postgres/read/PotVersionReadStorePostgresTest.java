package com.kartaguez.pocoma.infra.postgres.read;
import com.kartaguez.pocoma.domain.projection.*;
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
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?, 'read_pot_version_node','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?, 'read_pot_user_set','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertThrows(RuntimeException.class,() -> f.oracle.update("insert into read_current_pot values (?,1)",UUID.randomUUID()));
        var p=UUID.randomUUID(); var s=new JdbcPotVersionStore(f.source,f.tx); f.write(() -> s.insertAndAdvance(value(p,1,false)));
        assertThrows(RuntimeException.class,() -> f.oracle.update("delete from read_pot_version where pot_id=?",p));
        assertThrows(RuntimeException.class,() -> s.insertAndAdvance(value(p,2,false)));
        f.evidence("migration privileges FK owner",f.oracle.queryForList("select version from flyway_schema_history where success"));
    }

    @Test void radixLargeTreeReuseAndCollisionDetectionWithRollback() throws Exception {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotVersionStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var rnd = new Random(10101L);
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();

        var shareholders = new ArrayList<PotVersion.Shareholder>();
        for (int i = 0; i < 70; i++) {
            shareholders.add(new PotVersion.Shareholder(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false, i % 3 == 0));
        }
        var expenses = new ArrayList<PotVersion.Expense>();
        for (int i = 0; i < 150; i++) {
            expenses.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }

        var v1 = new PotVersion(p, 1, 1, 1, false, shareholders, expenses, Set.of(u1, u2), Set.of(u1));
        f.write(() -> s.insertAndAdvance(v1));
        assertEquals(v1, f.tx.read(c -> s.read(p, 1, c).orElseThrow()));

        int nodesAfterV1 = f.oracle.queryForObject("select count(*) from read_pot_version_node", Integer.class);
        int userSetsAfterV1 = f.oracle.queryForObject("select count(*) from read_pot_user_set", Integer.class);
        assertEquals(2, userSetsAfterV1); // {u1,u2} and {u1}

        // V2 changes only pot details -> 100% node & user set reuse
        var v2 = new PotVersion(p, 2, 1, 2, false, shareholders, expenses, Set.of(u1, u2), Set.of(u1));
        f.write(() -> s.insertAndAdvance(v2));
        assertEquals(v2, f.tx.read(c -> s.read(p, 2, c).orElseThrow()));
        assertEquals(nodesAfterV1, f.oracle.queryForObject("select count(*) from read_pot_version_node", Integer.class));
        assertEquals(userSetsAfterV1, f.oracle.queryForObject("select count(*) from read_pot_user_set", Integer.class));

        // Simulate hash collision on a leaf node -> must throw IllegalStateException and roll back completely
        var existingLeafHash = f.oracle.queryForObject(
                "select node_hash from read_pot_version_node where node_kind='L' and tree_kind='E' limit 1",
                byte[].class);
        var collidingLeaf = new com.kartaguez.pocoma.domain.projection.radix.Radix16Node(
                com.kartaguez.pocoma.domain.projection.radix.ContentHash.of(existingLeafHash),
                1, 'L', 'E', 1, "", null, new byte[41]);
        var bogusManifest = new com.kartaguez.pocoma.port.projection.PotVersionManifest(
                p, 3, 1, 1, 3, false, 0, 1, null, collidingLeaf.hash(), null, null, Set.of(), Set.of());
        var collidingPlan = new com.kartaguez.pocoma.port.projection.PotVersionWritePlan(
                bogusManifest, List.of(), List.of(List.of(collidingLeaf)), 0, 0);
        var ex = assertThrows(RuntimeException.class, () -> f.write(() -> s.publishPrepared(collidingPlan)));
        assertTrue(ex instanceof IllegalStateException || ex.getCause() instanceof IllegalStateException);
        assertTrue(f.tx.read(c -> s.read(p, 3, c)).isEmpty(), "Failed collision must roll back manifest");
    }

    @Test void concurrentOverlappingRadixSubtreesStressF2() throws Exception {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotVersionStore(f.source, f.tx);
        var rnd = new Random(2026L);
        var sharedExpenses = new ArrayList<PotVersion.Expense>();
        for (int i = 0; i < 120; i++) {
            sharedExpenses.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }
        var u = UUID.randomUUID();
        int workers = 6;
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var barrier = new CyclicBarrier(workers);
            var futures = new ArrayList<Future<PotVersion>>();
            for (int w = 0; w < workers; w++) {
                var potId = UUID.randomUUID();
                var list = new ArrayList<>(sharedExpenses);
                list.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
                var pv = new PotVersion(potId, 1, 1, 1, false, List.of(), list, Set.of(u), Set.of());
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    f.write(() -> s.insertAndAdvance(pv));
                    return pv;
                }));
            }
            for (var fut : futures) {
                var expected = fut.get(30, TimeUnit.SECONDS);
                assertEquals(expected, f.tx.read(c -> s.read(expected.potId(), 1, c).orElseThrow()));
            }
        }
    }

    @Test void readLatestBeforeRespectsBoundsAndIgnoresCurrentPot() throws Exception {
        var f = new ReadStoreFixture(PG);
        var s = new JdbcPotVersionStore(f.source, f.tx);
        var p = UUID.randomUUID();
        var other = UUID.randomUUID();
        for (long v : new long[] {1, 10, 5}) {
            f.write(() -> s.insertAndAdvance(value(p, v, false)));
        }
        f.write(() -> s.insertAndAdvance(value(other, 9, false)));
        long current = f.tx.<Long>read(c -> s.currentVersion(p, c).orElseThrow());
        assertEquals(10L, current);

        var kFor8 = f.tx.read(c -> s.readLatestBefore(p, 8L, 1L, c));
        assertTrue(kFor8.isPresent());
        assertEquals(5L, kFor8.get().version());

        assertTrue(f.tx.read(c -> s.readLatestBefore(p, 8L, 6L, c)).isEmpty());

        var kFor5 = f.tx.read(c -> s.readLatestBefore(p, 5L, 1L, c));
        assertTrue(kFor5.isPresent());
        assertEquals(1L, kFor5.get().version());

        assertTrue(f.tx.read(c -> s.readLatestBefore(p, 1L, 1L, c)).isEmpty());
    }
}
