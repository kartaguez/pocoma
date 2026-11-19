package com.kartaguez.pocoma.domain.projection.radix;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Radix16TreeTest {

    private static final class InMemoryNodeStore implements Radix16Tree.NodeLoader {
        final Map<ContentHash, Radix16Node> nodes = new HashMap<>();

        void putAll(Map<ContentHash, Radix16Node> created) {
            for (Map.Entry<ContentHash, Radix16Node> e : created.entrySet()) {
                Radix16Node prev = nodes.putIfAbsent(e.getKey(), e.getValue());
                if (prev != null && !prev.exactContentEquals(e.getValue())) {
                    throw new AssertionError("Hash collision with divergent content: " + e.getKey());
                }
            }
        }

        @Override
        public Map<ContentHash, Radix16Node> loadBatch(Collection<ContentHash> hashes) {
            Map<ContentHash, Radix16Node> out = new HashMap<>();
            for (ContentHash h : hashes) {
                Radix16Node n = nodes.get(h);
                if (n != null) {
                    out.put(h, n);
                }
            }
            return out;
        }
    }

    @Test
    void thresholdCrossing63To64To65AndBackTo63MatchesFullBuildBitForBit() {
        Random rnd = new Random(20260417L);
        InMemoryNodeStore store = new InMemoryNodeStore();
        TreeMap<UUID, PotVersion.Expense> state = new TreeMap<>(SubobjectKey::compareUuid);
        List<UUID> insertionOrder = new ArrayList<>();
        ContentHash root = null;

        // Grow from 0 to 130 crossing 63, 64, 65
        for (int i = 1; i <= 130; i++) {
            UUID id = new UUID(rnd.nextLong(), rnd.nextLong());
            PotVersion.Expense e = new PotVersion.Expense(id, 1, 1, 1, false);
            state.put(id, e);
            insertionOrder.add(id);

            Radix16Tree.BuildResult inc = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_EXPENSES,
                    root,
                    Map.of(id, Radix16Codec.encodeExpense(e)),
                    Set.of(),
                    store);
            store.putAll(inc.newNodes());
            root = inc.rootHash();

            if (i <= 64) {
                assertTrue(store.nodes.get(root).isLeaf(), "Expected leaf at size " + i);
            } else {
                assertTrue(store.nodes.get(root).isInternal(), "Expected internal at size " + i);
            }

            if (i == 63 || i == 64 || i == 65 || i == 130) {
                assertFullEquivalence(root, state, store);
            }
        }

        // Shrink back from 130 to 63, crossing 65 -> 64 -> 63
        for (int i = 130; i > 62; i--) {
            UUID removeId = insertionOrder.removeLast();
            state.remove(removeId);
            Radix16Tree.BuildResult inc = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_EXPENSES,
                    root,
                    Map.of(),
                    Set.of(removeId),
                    store);
            store.putAll(inc.newNodes());
            root = inc.rootHash();

            if (i - 1 <= 64) {
                assertTrue(store.nodes.get(root).isLeaf(), "Expected collapsed leaf at size " + (i - 1));
            }
            if (i - 1 == 65 || i - 1 == 64 || i - 1 == 63) {
                assertFullEquivalence(root, state, store);
            }
        }

        // Empty tree
        Radix16Tree.BuildResult cleared = Radix16Tree.applyIncremental(
                Radix16Codec.TREE_EXPENSES, root, Map.of(), new HashSet<>(state.keySet()), store);
        assertNull(cleared.rootHash());
        assertTrue(cleared.newNodes().isEmpty());
    }

    @Test
    void correlatedUuidPrefixesSplitAndPathCompressIdenticallyToFullBuild() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        TreeMap<UUID, PotVersion.Expense> state = new TreeMap<>(SubobjectKey::compareUuid);
        ContentHash root = null;

        // 70 UUIDs sharing 24 identical leading nibbles (0xaaaa_bbbb_cccc_dddd_eeee_ffff_....)
        long msb = 0xaaaabbbbccccddddL;
        long lsbPrefix = 0xeeeeffff00000000L;
        List<UUID> correlated = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            UUID id = new UUID(msb, lsbPrefix | i);
            correlated.add(id);
            PotVersion.Expense e = new PotVersion.Expense(id, 1, 1, 1, false);
            state.put(id, e);
        }

        Radix16Tree.BuildResult initial = Radix16Tree.buildFullExpenses(new ArrayList<>(state.values()));
        store.putAll(initial.newNodes());
        root = initial.rootHash();
        Radix16Node rootNode = store.nodes.get(root);
        assertTrue(rootNode.isInternal());
        assertTrue(rootNode.prefix().length() >= 24, "Expected deep compressed prefix, got: " + rootNode.prefix());

        // Insert a diverging UUID at nibble 4 -> triggers prefix split
        UUID diverging = new UUID(0xaaaa111100000000L, 1L);
        PotVersion.Expense divExp = new PotVersion.Expense(diverging, 2, 3, 4, false);
        state.put(diverging, divExp);

        Radix16Tree.BuildResult split = Radix16Tree.applyIncremental(
                Radix16Codec.TREE_EXPENSES,
                root,
                Map.of(diverging, Radix16Codec.encodeExpense(divExp)),
                Set.of(),
                store);
        store.putAll(split.newNodes());
        root = split.rootHash();
        assertEquals("aaaa", store.nodes.get(root).prefix());
        assertFullEquivalence(root, state, store);

        // Remove the diverging UUID -> triggers path compression back to original root hash!
        state.remove(diverging);
        Radix16Tree.BuildResult compressed = Radix16Tree.applyIncremental(
                Radix16Codec.TREE_EXPENSES,
                root,
                Map.of(),
                Set.of(diverging),
                store);
        store.putAll(compressed.newNodes());
        assertEquals(initial.rootHash(), compressed.rootHash(), "Path compression must restore exact original hash");
        assertFullEquivalence(compressed.rootHash(), state, store);
    }

    @Test
    void outOfOrderAndNmDeterminismUpTo2000Entries() {
        Random rnd = new Random(42L);
        List<PotVersion.Expense> base = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            base.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }

        // Full build
        Radix16Tree.BuildResult full = Radix16Tree.buildFullExpenses(base);
        InMemoryNodeStore storeA = new InMemoryNodeStore();
        storeA.putAll(full.newNodes());

        // Incremental in 5 shuffled batches (N-m simulation)
        List<PotVersion.Expense> shuffled = new ArrayList<>(base);
        Collections.shuffle(shuffled, new Random(999L));
        InMemoryNodeStore storeB = new InMemoryNodeStore();
        ContentHash rootB = null;
        for (int start = 0; start < shuffled.size(); start += 400) {
            Map<UUID, Radix16Codec.EncodedEntry> batch = new HashMap<>();
            for (int i = start; i < Math.min(start + 400, shuffled.size()); i++) {
                PotVersion.Expense e = shuffled.get(i);
                batch.put(e.id(), Radix16Codec.encodeExpense(e));
            }
            Radix16Tree.BuildResult res = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_EXPENSES, rootB, batch, Set.of(), storeB);
            storeB.putAll(res.newNodes());
            rootB = res.rootHash();
        }
        assertEquals(full.rootHash(), rootB);

        // Apply multi-updates & logical deletes in two different orders
        List<PotVersion.Expense> mutations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PotVersion.Expense orig = base.get(i * 37);
            mutations.add(new PotVersion.Expense(orig.id(), 2, 3, 4, i % 3 == 0));
        }
        for (int i = 0; i < 20; i++) {
            mutations.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }

        // Path 1: single N-m batch
        Map<UUID, Radix16Codec.EncodedEntry> singleBatch = new HashMap<>();
        for (PotVersion.Expense e : mutations) {
            singleBatch.put(e.id(), Radix16Codec.encodeExpense(e));
        }
        Radix16Tree.BuildResult resSingle = Radix16Tree.applyIncremental(
                Radix16Codec.TREE_EXPENSES, full.rootHash(), singleBatch, Set.of(), storeA);
        storeA.putAll(resSingle.newNodes());

        // Path 2: one by one in reversed order
        List<PotVersion.Expense> reversed = new ArrayList<>(mutations);
        Collections.reverse(reversed);
        ContentHash cur = rootB;
        for (PotVersion.Expense e : reversed) {
            Radix16Tree.BuildResult step = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_EXPENSES, cur, Map.of(e.id(), Radix16Codec.encodeExpense(e)), Set.of(), storeB);
            storeB.putAll(step.newNodes());
            cur = step.rootHash();
        }

        assertEquals(resSingle.rootHash(), cur);

        // Verify full reconstruction order
        List<PotVersion.Expense> reconstructed = Radix16Tree.reconstructExpenses(cur, storeB.nodes);
        assertEquals(2020, reconstructed.size());
        for (int i = 1; i < reconstructed.size(); i++) {
            assertTrue(SubobjectKey.compareUuid(reconstructed.get(i - 1).id(), reconstructed.get(i).id()) < 0);
        }
    }

    @Test
    void bottomUpGroupingSortsEachLevelByContentHashAndOrdersChildrenBeforeParents() {
        Random rnd = new Random(777L);
        List<PotVersion.Shareholder> shareholders = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            shareholders.add(new PotVersion.Shareholder(
                    new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false, i % 2 == 0));
        }
        Radix16Tree.BuildResult res = Radix16Tree.buildFullShareholders(shareholders);
        List<List<Radix16Node>> levels = Radix16Tree.groupBottomUpByDepth(res.newNodes().values());
        assertTrue(levels.size() >= 2);

        Set<ContentHash> inserted = new HashSet<>();
        int total = 0;
        for (List<Radix16Node> level : levels) {
            // Check ascending ContentHash order within level
            for (int i = 1; i < level.size(); i++) {
                assertTrue(level.get(i - 1).hash().compareTo(level.get(i).hash()) < 0);
            }
            // Check all children of every internal node were already inserted in strictly lower levels
            for (Radix16Node n : level) {
                if (n.isInternal()) {
                    for (ContentHash child : n.childHashes()) {
                        assertTrue(inserted.contains(child), "Child must be inserted in a strictly lower level");
                    }
                }
            }
            for (Radix16Node n : level) {
                inserted.add(n.hash());
                total++;
            }
        }
        assertEquals(res.newNodes().size(), total);
        assertEquals(shareholders.size(), Radix16Tree.reconstructShareholders(res.rootHash(), res.newNodes()).size());
    }

    @Test
    void noOpIncrementalReusesRootAndCreatesZeroNodes() {
        UUID id = UUID.randomUUID();
        PotVersion.Expense e = new PotVersion.Expense(id, 1, 2, 3, false);
        Radix16Tree.BuildResult initial = Radix16Tree.buildFullExpenses(List.of(e));
        InMemoryNodeStore store = new InMemoryNodeStore();
        store.putAll(initial.newNodes());

        Radix16Tree.BuildResult noOp = Radix16Tree.applyIncremental(
                Radix16Codec.TREE_EXPENSES,
                initial.rootHash(),
                Map.of(id, Radix16Codec.encodeExpense(e)),
                Set.of(),
                store);
        assertEquals(initial.rootHash(), noOp.rootHash());
        assertTrue(noOp.newNodes().isEmpty());
    }

    private static void assertFullEquivalence(
            ContentHash incrementalRoot,
            TreeMap<UUID, PotVersion.Expense> state,
            InMemoryNodeStore store) {
        Radix16Tree.BuildResult full = Radix16Tree.buildFullExpenses(new ArrayList<>(state.values()));
        assertEquals(full.rootHash(), incrementalRoot, "Incremental root must match full build root");
        List<PotVersion.Expense> reconstructed = Radix16Tree.reconstructExpenses(incrementalRoot, store.nodes);
        assertEquals(new ArrayList<>(state.values()), reconstructed);
    }
}
