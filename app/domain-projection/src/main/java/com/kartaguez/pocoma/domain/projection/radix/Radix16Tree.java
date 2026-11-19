package com.kartaguez.pocoma.domain.projection.radix;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Codec.EncodedEntry;

import java.util.*;

/**
 * Canonical compressed persistent Radix-16 tree with grouped leaves (L = 64).
 *
 * <p>Canonical invariant for any non-empty key-value set S at nibble depth d in [0..32]:
 * <ul>
 *   <li>{@code |S| <= 64} &rArr; LEAF node ({@code nodeKind='L'}, {@code prefix=""}, entries sorted by unsigned 128-bit UUID).</li>
 *   <li>{@code |S| > 64} &rArr; INTERNAL node ({@code nodeKind='I'}, {@code prefix = LCP(S)[d..]}, 2..16 non-empty child buckets
 *       at {@code splitDepth = d + prefix.length()}, each child recursively canonical at {@code splitDepth + 1}).</li>
 *   <li>Empty set &rArr; {@code null} root hash.</li>
 * </ul>
 */
public final class Radix16Tree {
    public static final int MAX_LEAF_SIZE = 64;
    public static final int MAX_DEPTH = 32;

    private Radix16Tree() {}

    @FunctionalInterface
    public interface NodeLoader {
        Map<ContentHash, Radix16Node> loadBatch(Collection<ContentHash> hashes);
    }

    public record BuildResult(
            ContentHash rootHash,
            Map<ContentHash, Radix16Node> newNodes,
            int nodesLoaded,
            int nodesReused) {
        public BuildResult {
            newNodes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(newNodes)));
        }
    }

    public static BuildResult buildFullShareholders(List<PotVersion.Shareholder> shareholders) {
        Objects.requireNonNull(shareholders, "shareholders required");
        if (shareholders.isEmpty()) {
            return new BuildResult(null, Map.of(), 0, 0);
        }
        List<EncodedEntry> entries = new ArrayList<>(shareholders.size());
        for (PotVersion.Shareholder s : shareholders) {
            entries.add(Radix16Codec.encodeShareholder(s));
        }
        entries.sort((a, b) -> SubobjectKey.compareUuid(a.id(), b.id()));
        return buildFull(Radix16Codec.TREE_SHAREHOLDERS, entries);
    }

    public static BuildResult buildFullExpenses(List<PotVersion.Expense> expenses) {
        Objects.requireNonNull(expenses, "expenses required");
        if (expenses.isEmpty()) {
            return new BuildResult(null, Map.of(), 0, 0);
        }
        List<EncodedEntry> entries = new ArrayList<>(expenses.size());
        for (PotVersion.Expense e : expenses) {
            entries.add(Radix16Codec.encodeExpense(e));
        }
        entries.sort((a, b) -> SubobjectKey.compareUuid(a.id(), b.id()));
        return buildFull(Radix16Codec.TREE_EXPENSES, entries);
    }

    public static BuildResult buildFull(char treeKind, List<EncodedEntry> sortedEntries) {
        Objects.requireNonNull(sortedEntries, "sortedEntries required");
        if (sortedEntries.isEmpty()) {
            return new BuildResult(null, Map.of(), 0, 0);
        }
        for (int i = 1; i < sortedEntries.size(); i++) {
            if (SubobjectKey.compareUuid(sortedEntries.get(i - 1).id(), sortedEntries.get(i).id()) >= 0) {
                throw new IllegalArgumentException("Entries must be strictly sorted and unique by unsigned UUID");
            }
        }
        Map<ContentHash, Radix16Node> created = new LinkedHashMap<>();
        Radix16Node root = buildSubtree(treeKind, sortedEntries, 0, sortedEntries.size(), 0, created);
        return new BuildResult(root.hash(), created, 0, 0);
    }

    private static Radix16Node buildSubtree(
            char treeKind,
            List<EncodedEntry> sorted,
            int from,
            int to,
            int depth,
            Map<ContentHash, Radix16Node> created) {
        int count = to - from;
        if (count <= MAX_LEAF_SIZE) {
            Radix16Node leaf = Radix16Codec.makeLeaf(treeKind, sorted.subList(from, to));
            created.put(leaf.hash(), leaf);
            return leaf;
        }
        UUID first = sorted.get(from).id();
        UUID last = sorted.get(to - 1).id();
        StringBuilder prefix = new StringBuilder();
        int d = depth;
        while (d < MAX_DEPTH && Radix16Codec.uuidNibble(first, d) == Radix16Codec.uuidNibble(last, d)) {
            prefix.append(Radix16Codec.nibbleToHex(Radix16Codec.uuidNibble(first, d)));
            d++;
        }
        int splitDepth = d;
        int nibbleMask = 0;
        List<ContentHash> childHashes = new ArrayList<>(16);
        int[] countsBuf = new int[16];
        int childCount = 0;

        int i = from;
        while (i < to) {
            int nib = Radix16Codec.uuidNibble(sorted.get(i).id(), splitDepth);
            int j = i + 1;
            while (j < to && Radix16Codec.uuidNibble(sorted.get(j).id(), splitDepth) == nib) {
                j++;
            }
            Radix16Node child = buildSubtree(treeKind, sorted, i, j, splitDepth + 1, created);
            nibbleMask |= (1 << nib);
            childHashes.add(child.hash());
            countsBuf[childCount++] = child.entryCount();
            i = j;
        }
        Radix16Node internal = Radix16Codec.makeInternal(
                treeKind,
                prefix.toString(),
                nibbleMask,
                childHashes,
                Arrays.copyOf(countsBuf, childCount));
        created.put(internal.hash(), internal);
        return internal;
    }

    public static BuildResult applyIncremental(
            char treeKind,
            ContentHash baseRoot,
            Map<UUID, EncodedEntry> upserts,
            Set<UUID> removals,
            NodeLoader loader) {
        Objects.requireNonNull(upserts, "upserts required");
        Objects.requireNonNull(removals, "removals required");
        Objects.requireNonNull(loader, "loader required");
        if (upserts.isEmpty() && removals.isEmpty()) {
            return new BuildResult(baseRoot, Map.of(), 0, baseRoot == null ? 0 : 1);
        }
        List<EncodedEntry> sortedUpserts = new ArrayList<>(upserts.values());
        sortedUpserts.sort((a, b) -> SubobjectKey.compareUuid(a.id(), b.id()));
        List<UUID> sortedRemovals = new ArrayList<>(removals);
        sortedRemovals.sort(SubobjectKey::compareUuid);

        IncrementalContext ctx = new IncrementalContext(treeKind, loader);
        Radix16Node newRoot = applyNode(ctx, baseRoot, 0, sortedUpserts, sortedRemovals);
        if (newRoot == null) {
            return new BuildResult(null, Map.of(), ctx.nodesLoaded, ctx.nodesReused);
        }
        Map<ContentHash, Radix16Node> reachableCreated = pruneUnreachableCreated(newRoot, ctx.created);
        return new BuildResult(newRoot.hash(), reachableCreated, ctx.nodesLoaded, ctx.nodesReused);
    }

    private static Map<ContentHash, Radix16Node> pruneUnreachableCreated(
            Radix16Node root, Map<ContentHash, Radix16Node> created) {
        if (created.isEmpty()) {
            return Map.of();
        }
        Map<ContentHash, Radix16Node> reachable = new LinkedHashMap<>();
        Deque<Radix16Node> stack = new ArrayDeque<>();
        if (created.containsKey(root.hash())) {
            reachable.put(root.hash(), root);
            stack.push(root);
        }
        while (!stack.isEmpty()) {
            Radix16Node current = stack.pop();
            if (current.isInternal()) {
                for (ContentHash ch : current.childHashes()) {
                    Radix16Node createdChild = created.get(ch);
                    if (createdChild != null && reachable.putIfAbsent(ch, createdChild) == null) {
                        stack.push(createdChild);
                    }
                }
            }
        }
        return reachable;
    }

    private static final class IncrementalContext {
        final char treeKind;
        final NodeLoader loader;
        final Map<ContentHash, Radix16Node> loadedCache = new HashMap<>();
        final Map<ContentHash, Radix16Node> created = new LinkedHashMap<>();
        int nodesLoaded = 0;
        int nodesReused = 0;

        IncrementalContext(char treeKind, NodeLoader loader) {
            this.treeKind = treeKind;
            this.loader = loader;
        }

        Radix16Node load(ContentHash h) {
            Radix16Node n = created.get(h);
            if (n != null) {
                return n;
            }
            n = loadedCache.get(h);
            if (n != null) {
                return n;
            }
            Map<ContentHash, Radix16Node> fetched = loader.loadBatch(List.of(h));
            Radix16Node found = fetched.get(h);
            if (found == null) {
                throw new IllegalStateException("Missing Radix-16 node: " + h);
            }
            validateLoadedNode(found);
            loadedCache.put(h, found);
            nodesLoaded++;
            return found;
        }

        void prefetch(Collection<ContentHash> hashes) {
            List<ContentHash> missing = new ArrayList<>();
            for (ContentHash h : hashes) {
                if (!created.containsKey(h) && !loadedCache.containsKey(h)) {
                    missing.add(h);
                }
            }
            if (!missing.isEmpty()) {
                Map<ContentHash, Radix16Node> fetched = loader.loadBatch(missing);
                for (ContentHash h : missing) {
                    Radix16Node n = fetched.get(h);
                    if (n == null) {
                        throw new IllegalStateException("Missing Radix-16 node: " + h);
                    }
                    validateLoadedNode(n);
                    loadedCache.put(h, n);
                    nodesLoaded++;
                }
            }
        }

        private void validateLoadedNode(Radix16Node node) {
            if (node.treeKind() != treeKind) {
                throw new IllegalStateException("Node treeKind mismatch: expected " + treeKind + ", got " + node.treeKind());
            }
        }
    }

    private static Radix16Node applyNode(
            IncrementalContext ctx,
            ContentHash nodeHash,
            int depth,
            List<EncodedEntry> upserts,
            List<UUID> removals) {
        if (upserts.isEmpty() && removals.isEmpty()) {
            if (nodeHash != null) {
                ctx.nodesReused++;
                return ctx.load(nodeHash);
            }
            return null;
        }
        if (nodeHash == null) {
            if (upserts.isEmpty()) {
                return null;
            }
            return buildSubtree(ctx.treeKind, upserts, 0, upserts.size(), depth, ctx.created);
        }

        Radix16Node node = ctx.load(nodeHash);

        // Case 1: LEAF
        if (node.isLeaf()) {
            TreeMap<UUID, EncodedEntry> merged = new TreeMap<>(SubobjectKey::compareUuid);
            for (EncodedEntry e : Radix16Codec.decodeLeafEntries(node)) {
                merged.put(e.id(), e);
            }
            boolean changed = false;
            for (UUID r : removals) {
                if (merged.remove(r) != null) {
                    changed = true;
                }
            }
            for (EncodedEntry u : upserts) {
                EncodedEntry prev = merged.put(u.id(), u);
                if (prev == null || !Arrays.equals(prev.rawBytes(), u.rawBytes())) {
                    changed = true;
                }
            }
            if (!changed) {
                ctx.nodesReused++;
                return node;
            }
            if (merged.isEmpty()) {
                return null;
            }
            List<EncodedEntry> list = new ArrayList<>(merged.values());
            return buildSubtree(ctx.treeKind, list, 0, list.size(), depth, ctx.created);
        }

        // Case 2: INTERNAL
        String prefix = node.prefix();
        int prefixLen = prefix.length();

        List<EncodedEntry> matchingUpserts = new ArrayList<>();
        List<EncodedEntry> divergingUpserts = new ArrayList<>();
        int minDivIdx = prefixLen;

        for (EncodedEntry u : upserts) {
            int div = firstDivergence(u.id(), depth, prefix);
            if (div == prefixLen) {
                matchingUpserts.add(u);
            } else {
                divergingUpserts.add(u);
                if (div < minDivIdx) {
                    minDivIdx = div;
                }
            }
        }
        List<UUID> matchingRemovals = new ArrayList<>();
        for (UUID r : removals) {
            if (firstDivergence(r, depth, prefix) == prefixLen) {
                matchingRemovals.add(r);
            }
        }

        // Subcase 2a: At least one upsert diverges from node.prefix
        if (!divergingUpserts.isEmpty()) {
            Radix16Node updatedOld = (matchingUpserts.isEmpty() && matchingRemovals.isEmpty())
                    ? node
                    : applyMatchingInternal(ctx, node, depth, matchingUpserts, matchingRemovals);

            if (updatedOld == null) {
                return buildSubtree(ctx.treeKind, divergingUpserts, 0, divergingUpserts.size(), depth, ctx.created);
            }

            if (updatedOld.entryCount() + divergingUpserts.size() <= MAX_LEAF_SIZE) {
                TreeMap<UUID, EncodedEntry> all = new TreeMap<>(SubobjectKey::compareUuid);
                collectAllEntries(ctx, updatedOld, all);
                for (EncodedEntry e : divergingUpserts) {
                    all.put(e.id(), e);
                }
                List<EncodedEntry> list = new ArrayList<>(all.values());
                return buildSubtree(ctx.treeKind, list, 0, list.size(), depth, ctx.created);
            }

            int divDepth = depth + minDivIdx;
            String topPrefix = prefix.substring(0, minDivIdx);
            int oldBranchNibble = Radix16Codec.hexToNibble(prefix.charAt(minDivIdx));

            Radix16Node shiftedOld;
            if (updatedOld.isLeaf()) {
                shiftedOld = updatedOld;
                if (updatedOld == node) {
                    ctx.nodesReused++;
                }
            } else {
                String subPrefix = updatedOld.prefix().substring(minDivIdx + 1);
                shiftedOld = Radix16Codec.makeInternal(
                        ctx.treeKind,
                        subPrefix,
                        updatedOld.nibbleMask(),
                        updatedOld.childHashes(),
                        updatedOld.childEntryCounts());
                ctx.created.put(shiftedOld.hash(), shiftedOld);
                ctx.nodesReused += updatedOld.childHashes().size();
            }

            @SuppressWarnings("unchecked")
            List<EncodedEntry>[] buckets = new List[16];
            for (EncodedEntry e : divergingUpserts) {
                int nib = Radix16Codec.uuidNibble(e.id(), divDepth);
                if (buckets[nib] == null) {
                    buckets[nib] = new ArrayList<>();
                }
                buckets[nib].add(e);
            }

            int nibbleMask = 0;
            List<ContentHash> childHashes = new ArrayList<>(16);
            int[] countsBuf = new int[16];
            int childCount = 0;

            for (int nib = 0; nib < 16; nib++) {
                if (nib == oldBranchNibble) {
                    Radix16Node branch = buckets[nib] != null
                            ? applyNode(ctx, shiftedOld.hash(), divDepth + 1, buckets[nib], List.of())
                            : shiftedOld;
                    nibbleMask |= (1 << nib);
                    childHashes.add(branch.hash());
                    countsBuf[childCount++] = branch.entryCount();
                } else if (buckets[nib] != null) {
                    Radix16Node branch = buildSubtree(
                            ctx.treeKind, buckets[nib], 0, buckets[nib].size(), divDepth + 1, ctx.created);
                    nibbleMask |= (1 << nib);
                    childHashes.add(branch.hash());
                    countsBuf[childCount++] = branch.entryCount();
                }
            }
            Radix16Node top = Radix16Codec.makeInternal(
                    ctx.treeKind,
                    topPrefix,
                    nibbleMask,
                    childHashes,
                    Arrays.copyOf(countsBuf, childCount));
            ctx.created.put(top.hash(), top);
            return top;
        }

        // Subcase 2b: All mutations match node.prefix
        return applyMatchingInternal(ctx, node, depth, matchingUpserts, matchingRemovals);
    }

    private static Radix16Node applyMatchingInternal(
            IncrementalContext ctx,
            Radix16Node node,
            int depth,
            List<EncodedEntry> matchingUpserts,
            List<UUID> matchingRemovals) {
        if (matchingUpserts.isEmpty() && matchingRemovals.isEmpty()) {
            ctx.nodesReused++;
            return node;
        }
        String prefix = node.prefix();
        int splitDepth = depth + prefix.length();

        @SuppressWarnings("unchecked")
        List<EncodedEntry>[] upsertBuckets = new List[16];
        @SuppressWarnings("unchecked")
        List<UUID>[] removalBuckets = new List[16];
        int touchedMask = 0;

        for (EncodedEntry u : matchingUpserts) {
            int nib = Radix16Codec.uuidNibble(u.id(), splitDepth);
            if (upsertBuckets[nib] == null) {
                upsertBuckets[nib] = new ArrayList<>();
            }
            upsertBuckets[nib].add(u);
            touchedMask |= (1 << nib);
        }
        for (UUID r : matchingRemovals) {
            int nib = Radix16Codec.uuidNibble(r, splitDepth);
            if (removalBuckets[nib] == null) {
                removalBuckets[nib] = new ArrayList<>();
            }
            removalBuckets[nib].add(r);
            touchedMask |= (1 << nib);
        }

        List<ContentHash> toPrefetch = new ArrayList<>();
        for (int nib = 0; nib < 16; nib++) {
            if ((touchedMask & (1 << nib)) != 0) {
                int idx = node.childIndexForNibble(nib);
                if (idx >= 0) {
                    toPrefetch.add(node.childHashes().get(idx));
                }
            }
        }
        ctx.prefetch(toPrefetch);

        int oldMask = node.nibbleMask();
        int[] oldCounts = node.childEntryCounts();
        List<ContentHash> oldChildren = node.childHashes();

        int newMask = 0;
        List<ContentHash> newChildren = new ArrayList<>(16);
        int[] newCountsBuf = new int[16];
        int newChildCount = 0;
        int totalEntries = 0;
        boolean anyChanged = false;

        for (int nib = 0; nib < 16; nib++) {
            int oldIdx = node.childIndexForNibble(nib);
            ContentHash oldHash = oldIdx >= 0 ? oldChildren.get(oldIdx) : null;
            if ((touchedMask & (1 << nib)) == 0) {
                if (oldHash != null) {
                    newMask |= (1 << nib);
                    newChildren.add(oldHash);
                    newCountsBuf[newChildCount++] = oldCounts[oldIdx];
                    totalEntries += oldCounts[oldIdx];
                    ctx.nodesReused++;
                }
            } else {
                List<EncodedEntry> ups = upsertBuckets[nib] != null ? upsertBuckets[nib] : List.of();
                List<UUID> rems = removalBuckets[nib] != null ? removalBuckets[nib] : List.of();
                Radix16Node updatedChild = applyNode(ctx, oldHash, splitDepth + 1, ups, rems);
                ContentHash newHash = updatedChild == null ? null : updatedChild.hash();
                if (!Objects.equals(oldHash, newHash)) {
                    anyChanged = true;
                }
                if (updatedChild != null) {
                    newMask |= (1 << nib);
                    newChildren.add(newHash);
                    newCountsBuf[newChildCount++] = updatedChild.entryCount();
                    totalEntries += updatedChild.entryCount();
                }
            }
        }

        if (!anyChanged && newMask == oldMask) {
            ctx.nodesReused++;
            return node;
        }
        if (totalEntries == 0) {
            return null;
        }
        if (totalEntries <= MAX_LEAF_SIZE) {
            ctx.prefetch(newChildren);
            TreeMap<UUID, EncodedEntry> all = new TreeMap<>(SubobjectKey::compareUuid);
            for (ContentHash ch : newChildren) {
                collectAllEntries(ctx, ctx.load(ch), all);
            }
            List<EncodedEntry> list = new ArrayList<>(all.values());
            return buildSubtree(ctx.treeKind, list, 0, list.size(), depth, ctx.created);
        }
        if (newChildCount == 1) {
            int onlyNib = Integer.numberOfTrailingZeros(newMask);
            Radix16Node onlyChild = ctx.load(newChildren.getFirst());
            if (onlyChild.isLeaf()) {
                throw new IllegalStateException("Leaf exceeds MAX_LEAF_SIZE");
            }
            String mergedPrefix = prefix + Radix16Codec.nibbleToHex(onlyNib) + onlyChild.prefix();
            Radix16Node merged = Radix16Codec.makeInternal(
                    ctx.treeKind,
                    mergedPrefix,
                    onlyChild.nibbleMask(),
                    onlyChild.childHashes(),
                    onlyChild.childEntryCounts());
            ctx.created.put(merged.hash(), merged);
            return merged;
        }

        Radix16Node updated = Radix16Codec.makeInternal(
                ctx.treeKind,
                prefix,
                newMask,
                newChildren,
                Arrays.copyOf(newCountsBuf, newChildCount));
        ctx.created.put(updated.hash(), updated);
        return updated;
    }

    private static void collectAllEntries(
            IncrementalContext ctx, Radix16Node node, TreeMap<UUID, EncodedEntry> out) {
        if (node.isLeaf()) {
            for (EncodedEntry e : Radix16Codec.decodeLeafEntries(node)) {
                out.put(e.id(), e);
            }
            return;
        }
        ctx.prefetch(node.childHashes());
        for (ContentHash ch : node.childHashes()) {
            collectAllEntries(ctx, ctx.load(ch), out);
        }
    }

    public static int firstDivergence(UUID id, int depth, String prefix) {
        int len = prefix.length();
        for (int i = 0; i < len; i++) {
            if (Radix16Codec.uuidNibble(id, depth + i) != Radix16Codec.hexToNibble(prefix.charAt(i))) {
                return i;
            }
        }
        return len;
    }

    public static List<PotVersion.Shareholder> reconstructShareholders(
            ContentHash rootHash, Map<ContentHash, Radix16Node> nodes) {
        if (rootHash == null) {
            return List.of();
        }
        Radix16Node root = nodes.get(rootHash);
        if (root == null) {
            throw new IllegalStateException("Missing shareholder root node: " + rootHash);
        }
        if (root.treeKind() != Radix16Codec.TREE_SHAREHOLDERS) {
            throw new IllegalStateException("Expected shareholder root node, got treeKind " + root.treeKind());
        }
        List<PotVersion.Shareholder> out = new ArrayList<>(root.entryCount());
        traverseShareholders(root, nodes, out, 0);
        if (out.size() != root.entryCount()) {
            throw new IllegalStateException("Reconstructed shareholder count mismatch: expected " + root.entryCount() + ", got " + out.size());
        }
        return List.copyOf(out);
    }

    private static void traverseShareholders(
            Radix16Node node, Map<ContentHash, Radix16Node> nodes, List<PotVersion.Shareholder> out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Radix-16 tree depth exceeded " + MAX_DEPTH);
        }
        if (node.treeKind() != Radix16Codec.TREE_SHAREHOLDERS) {
            throw new IllegalStateException("Cross-tree node reference detected");
        }
        if (node.isLeaf()) {
            byte[] payload = node.rawPayload();
            int count = node.entryCount();
            for (int i = 0; i < count; i++) {
                PotVersion.Shareholder s = Radix16Codec.decodeShareholder(payload, i * Radix16Codec.ENTRY_BYTES);
                if (!out.isEmpty() && SubobjectKey.compareUuid(out.getLast().id(), s.id()) >= 0) {
                    throw new IllegalStateException("Non-canonical shareholder order during reconstruction");
                }
                out.add(s);
            }
            return;
        }
        int nextDepth = depth + node.prefix().length() + 1;
        for (ContentHash ch : node.childHashes()) {
            Radix16Node child = nodes.get(ch);
            if (child == null) {
                throw new IllegalStateException("Incomplete shareholder tree: missing child " + ch);
            }
            traverseShareholders(child, nodes, out, nextDepth);
        }
    }

    public static List<PotVersion.Expense> reconstructExpenses(
            ContentHash rootHash, Map<ContentHash, Radix16Node> nodes) {
        if (rootHash == null) {
            return List.of();
        }
        Radix16Node root = nodes.get(rootHash);
        if (root == null) {
            throw new IllegalStateException("Missing expense root node: " + rootHash);
        }
        if (root.treeKind() != Radix16Codec.TREE_EXPENSES) {
            throw new IllegalStateException("Expected expense root node, got treeKind " + root.treeKind());
        }
        List<PotVersion.Expense> out = new ArrayList<>(root.entryCount());
        traverseExpenses(root, nodes, out, 0);
        if (out.size() != root.entryCount()) {
            throw new IllegalStateException("Reconstructed expense count mismatch: expected " + root.entryCount() + ", got " + out.size());
        }
        return List.copyOf(out);
    }

    private static void traverseExpenses(
            Radix16Node node, Map<ContentHash, Radix16Node> nodes, List<PotVersion.Expense> out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Radix-16 tree depth exceeded " + MAX_DEPTH);
        }
        if (node.treeKind() != Radix16Codec.TREE_EXPENSES) {
            throw new IllegalStateException("Cross-tree node reference detected");
        }
        if (node.isLeaf()) {
            byte[] payload = node.rawPayload();
            int count = node.entryCount();
            for (int i = 0; i < count; i++) {
                PotVersion.Expense e = Radix16Codec.decodeExpense(payload, i * Radix16Codec.ENTRY_BYTES);
                if (!out.isEmpty() && SubobjectKey.compareUuid(out.getLast().id(), e.id()) >= 0) {
                    throw new IllegalStateException("Non-canonical expense order during reconstruction");
                }
                out.add(e);
            }
            return;
        }
        int nextDepth = depth + node.prefix().length() + 1;
        for (ContentHash ch : node.childHashes()) {
            Radix16Node child = nodes.get(ch);
            if (child == null) {
                throw new IllegalStateException("Incomplete expense tree: missing child " + ch);
            }
            traverseExpenses(child, nodes, out, nextDepth);
        }
    }

    /**
     * Groups new nodes bottom-up by dependency level (level 0 first, root last) and sorts
     * each level strictly by ascending {@link ContentHash} for deadlock-free concurrent insertion.
     */
    public static List<List<Radix16Node>> groupBottomUpByDepth(Collection<Radix16Node> nodes) {
        Objects.requireNonNull(nodes, "nodes required");
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<ContentHash, Radix16Node> byHash = new HashMap<>(nodes.size() * 2);
        for (Radix16Node node : nodes) {
            Radix16Node prev = byHash.put(node.hash(), node);
            if (prev != null && !prev.exactContentEquals(node)) {
                throw new IllegalStateException("Hash collision with divergent content: " + node.hash());
            }
        }
        Map<ContentHash, Integer> levels = new HashMap<>(byHash.size() * 2);
        Set<ContentHash> visiting = new HashSet<>();
        int maxLevel = 0;
        for (ContentHash h : byHash.keySet()) {
            int lvl = computeBottomUpLevel(h, byHash, levels, visiting);
            if (lvl > maxLevel) {
                maxLevel = lvl;
            }
        }
        List<List<Radix16Node>> grouped = new ArrayList<>(maxLevel + 1);
        for (int i = 0; i <= maxLevel; i++) {
            grouped.add(new ArrayList<>());
        }
        for (Map.Entry<ContentHash, Radix16Node> entry : byHash.entrySet()) {
            grouped.get(levels.get(entry.getKey())).add(entry.getValue());
        }
        List<List<Radix16Node>> result = new ArrayList<>(grouped.size());
        for (List<Radix16Node> levelNodes : grouped) {
            levelNodes.sort(Comparator.comparing(Radix16Node::hash));
            result.add(List.copyOf(levelNodes));
        }
        return List.copyOf(result);
    }

    private static int computeBottomUpLevel(
            ContentHash hash,
            Map<ContentHash, Radix16Node> byHash,
            Map<ContentHash, Integer> memo,
            Set<ContentHash> visiting) {
        Integer cached = memo.get(hash);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(hash)) {
            throw new IllegalStateException("Cycle detected in Radix-16 node graph at " + hash);
        }
        Radix16Node node = byHash.get(hash);
        int level;
        if (node.isLeaf()) {
            level = 0;
        } else {
            int maxChildLevel = -1;
            for (ContentHash child : node.childHashes()) {
                if (byHash.containsKey(child)) {
                    int childLevel = computeBottomUpLevel(child, byHash, memo, visiting);
                    if (childLevel > maxChildLevel) {
                        maxChildLevel = childLevel;
                    }
                }
            }
            level = maxChildLevel + 1;
        }
        visiting.remove(hash);
        memo.put(hash, level);
        return level;
    }
}
