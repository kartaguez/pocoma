package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.radix.*;

import java.util.*;

/** Pre-built atomic write plan for publishing a Radix-16 PotVersion inside one READ_COMMITTED transaction. */
public record PotVersionWritePlan(
        PotVersionManifest manifest,
        List<PotUserSet> userSetsToInsert,
        List<List<Radix16Node>> nodeLevelsBottomUp,
        int nodesLoaded,
        int nodesReused) {

    public PotVersionWritePlan {
        Objects.requireNonNull(manifest, "manifest required");
        userSetsToInsert = deduplicateAndSortUserSets(Objects.requireNonNull(userSetsToInsert, "userSetsToInsert required"));
        Objects.requireNonNull(nodeLevelsBottomUp, "nodeLevelsBottomUp required");
        List<List<Radix16Node>> copiedLevels = new ArrayList<>(nodeLevelsBottomUp.size());
        for (List<Radix16Node> level : nodeLevelsBottomUp) {
            copiedLevels.add(List.copyOf(level));
        }
        nodeLevelsBottomUp = List.copyOf(copiedLevels);
    }

    public int newNodeCount() {
        int total = 0;
        for (List<Radix16Node> level : nodeLevelsBottomUp) {
            total += level.size();
        }
        return total;
    }

    public static PotVersionWritePlan fromFull(PotVersion value) {
        Objects.requireNonNull(value, "value required");
        Radix16Tree.BuildResult sh = Radix16Tree.buildFullShareholders(value.shareholders());
        Radix16Tree.BuildResult ex = Radix16Tree.buildFullExpenses(value.expenses());

        Optional<PotUserSet> authSet = PotUserSet.of(value.authorizedUserIds());
        Optional<PotUserSet> attachedSet = PotUserSet.of(value.attachedActiveUserIds());

        List<PotUserSet> userSets = new ArrayList<>(2);
        authSet.ifPresent(userSets::add);
        attachedSet.ifPresent(userSets::add);

        List<Radix16Node> allNodes = new ArrayList<>(sh.newNodes().size() + ex.newNodes().size());
        allNodes.addAll(sh.newNodes().values());
        allNodes.addAll(ex.newNodes().values());
        List<List<Radix16Node>> levels = Radix16Tree.groupBottomUpByDepth(allNodes);

        PotVersionManifest manifest = new PotVersionManifest(
                value.potId(),
                value.version(),
                Radix16Codec.SCHEMA_VERSION,
                value.headerVersion(),
                value.detailsVersion(),
                value.deleted(),
                value.shareholders().size(),
                value.expenses().size(),
                sh.rootHash(),
                ex.rootHash(),
                authSet.map(PotUserSet::hash).orElse(null),
                attachedSet.map(PotUserSet::hash).orElse(null),
                value.authorizedUserIds(),
                value.attachedActiveUserIds());
        return new PotVersionWritePlan(manifest, userSets, levels, 0, 0);
    }

    private static List<PotUserSet> deduplicateAndSortUserSets(List<PotUserSet> sets) {
        if (sets.isEmpty()) {
            return List.of();
        }
        TreeMap<ContentHash, PotUserSet> byHash = new TreeMap<>();
        for (PotUserSet set : sets) {
            PotUserSet prev = byHash.put(set.hash(), set);
            if (prev != null && !prev.exactContentEquals(set)) {
                throw new IllegalStateException("Hash collision between PotUserSets: " + set.hash());
            }
        }
        return List.copyOf(byHash.values());
    }
}
