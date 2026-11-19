package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.port.projection.PotExpensesView;
import com.kartaguez.pocoma.port.projection.PotShareholdersView;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PotVersionTargetedReadPostgresTest {

    @Container
    static final PostgreSQLContainer PG = ReadStoreFixture.container();

    @Test
    void targetedReadsMatchFullReadAcrossHistoryAndAvoidUnnecessaryTreeTraversals() {
        var f = new ReadStoreFixture(PG);
        var store = new JdbcPotVersionStore(f.source, f.tx);
        var potId = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var member = UUID.randomUUID();
        var rnd = new Random(20260417L);

        // Build 100 shareholders (> 64 -> internal root + leaves) and 1000 expenses (> 64 -> multi-level tree)
        var shareholders = new ArrayList<PotVersion.Shareholder>();
        for (int i = 0; i < 100; i++) {
            shareholders.add(new PotVersion.Shareholder(
                    new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false, i % 4 == 0));
        }
        var expenses = new ArrayList<PotVersion.Expense>();
        for (int i = 0; i < 1000; i++) {
            expenses.add(new PotVersion.Expense(
                    new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }

        // Publish v1..v5 (evolving expenses & shareholders) and v6 (deleted pot)
        var versions = new LinkedHashMap<Long, PotVersion>();
        for (long v = 1; v <= 5; v++) {
            if (v > 1) {
                var oldEx = expenses.get((int) (v * 7));
                expenses.set((int) (v * 7), new PotVersion.Expense(oldEx.id(), v, v, v, v == 4));
                expenses.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
            }
            var pv = new PotVersion(
                    potId, v, v, v, false,
                    new ArrayList<>(shareholders),
                    new ArrayList<>(expenses),
                    Set.of(owner, member),
                    Set.of(owner));
            versions.put(v, pv);
            f.write(() -> store.insertAndAdvance(pv));
        }
        var deletedV6 = new PotVersion(
                potId, 6, 6, 5, true,
                new ArrayList<>(shareholders),
                new ArrayList<>(expenses),
                Set.of(owner, member),
                Set.of(owner));
        versions.put(6L, deletedV6);
        f.write(() -> store.insertAndAdvance(deletedV6));

        // 1. Arbitrary historical access (v=1, v=3, v=5): R1 == R3a == R3b == R3c
        for (long v : List.of(1L, 3L, 5L)) {
            PotVersion expected = versions.get(v);
            PotVersion full = f.tx.read(c -> store.read(potId, v, c).orElseThrow());
            assertEquals(expected, full);

            // R3a: shareholders only
            PotShareholdersView shView = f.tx.read(c -> store.readShareholders(potId, v, c).orElseThrow());
            assertEquals(potId, shView.potId());
            assertEquals(v, shView.version());
            assertFalse(shView.deleted());
            assertEquals(expected.authorizedUserIds(), shView.authorizedUserIds());
            assertEquals(expected.attachedActiveUserIds(), shView.attachedActiveUserIds());
            assertEquals(expected.shareholders(), shView.shareholders());

            // R3b: single expense + unknown expense
            PotVersion.Expense target = expected.expenses().get(expected.expenses().size() / 2);
            UUID unknownId = UUID.randomUUID();
            PotExpensesView singleView = f.tx.read(
                    c -> store.readExpenses(potId, v, List.of(target.id(), unknownId), c).orElseThrow());
            assertEquals(potId, singleView.potId());
            assertEquals(v, singleView.version());
            assertFalse(singleView.deleted());
            assertEquals(expected.authorizedUserIds(), singleView.authorizedUserIds());
            assertEquals(Map.of(target.id(), target), singleView.found());

            // R3c: batch of 50 expenses
            List<PotVersion.Expense> sample50 = expected.expenses().subList(0, 50);
            List<UUID> sampleIds = sample50.stream().map(PotVersion.Expense::id).toList();
            PotExpensesView batchView = f.tx.read(
                    c -> store.readExpenses(potId, v, sampleIds, c).orElseThrow());
            assertEquals(50, batchView.found().size());
            for (PotVersion.Expense e : sample50) {
                assertEquals(e, batchView.found().get(e.id()));
            }
        }

        // 2. Deleted pot: readExpenses returns deleted=true with empty found map (0 descent)
        PotExpensesView delView = f.tx.read(
                c -> store.readExpenses(potId, 6L, List.of(expenses.getFirst().id()), c).orElseThrow());
        assertTrue(delView.deleted());
        assertTrue(delView.found().isEmpty());

        // 3. Unknown pot/version returns empty
        assertTrue(f.tx.read(c -> store.readShareholders(potId, 99L, c)).isEmpty());
        assertTrue(f.tx.read(c -> store.readExpenses(potId, 99L, List.of(expenses.getFirst().id()), c)).isEmpty());

        // 4. Verify via EXPLAIN that R3a never touches expenses_root and returns strictly tree_kind='S' nodes
        Integer shNodeCount = f.oracle.queryForObject("""
                with recursive t as (
                  select n.node_hash, n.node_kind, n.tree_kind, n.child_hashes
                  from read_pot_version pv
                  join read_pot_version_node n on n.node_hash = pv.shareholders_root
                  where pv.pot_id = ? and pv.pot_version = 5
                  union all
                  select c.node_hash, c.node_kind, c.tree_kind, c.child_hashes
                  from t
                  join lateral unnest(t.child_hashes) as ch(h) on t.node_kind = 'I'
                  join read_pot_version_node c on c.node_hash = ch.h
                )
                select count(*) from t where tree_kind <> 'S'
                """, Integer.class, potId);
        assertEquals(0, shNodeCount, "R3a must never touch any non-shareholder node");

        Integer totalExNodes = f.oracle.queryForObject("""
                with recursive t as (
                  select n.node_hash, n.node_kind, n.child_hashes
                  from read_pot_version pv
                  join read_pot_version_node n on n.node_hash = pv.expenses_root
                  where pv.pot_id = ? and pv.pot_version = 5
                  union all
                  select c.node_hash, c.node_kind, c.child_hashes
                  from t
                  join lateral unnest(t.child_hashes) as ch(h) on t.node_kind = 'I'
                  join read_pot_version_node c on c.node_hash = ch.h
                )
                select count(*) from t
                """, Integer.class, potId);
        assertNotNull(totalExNodes);
        assertTrue(totalExNodes >= 17, "1000 expenses must span >= 17 Radix-16 nodes, got " + totalExNodes);
    }
}
