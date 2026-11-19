package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.projection.radix.*;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionObservation.*;
import com.kartaguez.pocoma.port.projection.*;
import java.time.Duration;
import java.util.*;

/** Inert participant; only L2 owns snapshots, final transaction and commit resolution. */
public final class MaterializePotVersion implements ConsumptionParticipant {
    public static final long DEFAULT_MAX_DELTA_DISTANCE = 64L;
    private static final int MIN_DELTA_VOLUME_FALLBACK = 64;

    private final PotVersionSource source;
    private final SubobjectVersionStore subobjects;
    private final PotVersionStore versions;
    private final long maxDeltaDistance;
    private final PotVersionObservation observation;

    public MaterializePotVersion(
            PotVersionSource source, SubobjectVersionStore subobjects, PotVersionStore versions) {
        this(source, subobjects, versions, DEFAULT_MAX_DELTA_DISTANCE, PotVersionObservation.NOOP);
    }

    public MaterializePotVersion(
            PotVersionSource source,
            SubobjectVersionStore subobjects,
            PotVersionStore versions,
            long maxDeltaDistance,
            PotVersionObservation observation) {
        this.source = Objects.requireNonNull(source);
        this.subobjects = Objects.requireNonNull(subobjects);
        this.versions = Objects.requireNonNull(versions);
        if (maxDeltaDistance < 0) {
            throw new IllegalArgumentException("Non-negative maxDeltaDistance required");
        }
        this.maxDeltaDistance = maxDeltaDistance;
        this.observation = Objects.requireNonNull(observation);
    }

    @Override
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        if (maxDeltaDistance == 0) {
            observation.pathSelected(Path.FULL, Reason.INCREMENTAL_DISABLED);
            return prepareFull(event, reads);
        }
        var eventRef = reads.source(c -> source.eventRef(event, c));
        if (eventRef.version() == 1) {
            observation.pathSelected(Path.FULL, Reason.INITIAL_VERSION);
            return prepareFull(event, reads);
        }
        var reference = lookupReference(eventRef, reads);
        if (reference.isEmpty()) {
            observation.pathSelected(Path.FULL, Reason.NO_REFERENCE);
            return prepareFull(event, reads);
        }
        return prepareIncrementalOrFallback(event, eventRef, reference.get(), reads);
    }

    private Optional<PotVersionManifest> lookupReference(
            PotVersionSource.EventRef eventRef, PreparationReads reads) {
        long minVersion = Math.max(1L, eventRef.version() - maxDeltaDistance);
        long start = System.nanoTime();
        final Optional<PotVersionManifest> refOpt;
        try {
            refOpt = reads.effects(
                    c -> versions.readManifestLatestBefore(
                            eventRef.potId(), eventRef.version(), minVersion, c));
        } catch (RuntimeException | Error fault) {
            if (fault instanceof IllegalStateException) {
                observation.referenceLookup(
                        LookupOutcome.CORRUPT, 0L, Duration.ofNanos(System.nanoTime() - start));
            }
            throw fault;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (refOpt.isEmpty()) {
            observation.referenceLookup(LookupOutcome.MISS, 0L, elapsed);
            return Optional.empty();
        }
        PotVersionManifest previous = refOpt.get();
        long distance = eventRef.version() - previous.version();
        if (!eventRef.potId().equals(previous.potId())
                || distance < 1
                || distance > maxDeltaDistance) {
            observation.referenceLookup(LookupOutcome.CORRUPT, 0L, elapsed);
            throw new IllegalStateException("Incompatible reference PotVersion manifest");
        }
        observation.referenceLookup(LookupOutcome.HIT, distance, elapsed);
        return Optional.of(previous);
    }

    private Optional<Prepared> prepareIncrementalOrFallback(
            UUID event,
            PotVersionSource.EventRef eventRef,
            PotVersionManifest previous,
            PreparationReads reads) {
        long deltaStart = System.nanoTime();
        var delta = reads.source(
                c -> source.stateDelta(
                        eventRef.potId(), previous.version(), eventRef.version(), c));
        observation.deltaResolved(
                delta.changedSubobjectCount(), Duration.ofNanos(System.nanoTime() - deltaStart));
        if (delta.isEmpty()) {
            observation.pathSelected(Path.FULL, Reason.EMPTY_DELTA);
            return prepareFull(event, reads);
        }
        if (isDeltaTooLarge(previous, delta)) {
            observation.pathSelected(Path.FULL, Reason.DELTA_TOO_LARGE);
            return prepareFull(event, reads);
        }

        long composeStart = System.nanoTime();
        Set<UUID> affectedShIds = PotVersionDeltaComposer.affectedShareholderIds(delta);
        Set<UUID> affectedExIds = PotVersionDeltaComposer.affectedExpenseIds(delta);
        Map<UUID, PotVersion.Shareholder> prevShareholders = affectedShIds.isEmpty()
                ? Map.of()
                : reads.effects(
                        c -> versions.readShareholdersByIds(
                                eventRef.potId(), previous.version(), affectedShIds, c));
        Map<UUID, PotVersion.Expense> prevExpenses = affectedExIds.isEmpty()
                ? Map.of()
                : reads.effects(
                        c -> versions.readExpensesByIds(
                                eventRef.potId(), previous.version(), affectedExIds, c));

        var composedOpt = PotVersionDeltaComposer.composeDelta(
                previous, eventRef.version(), prevShareholders, prevExpenses, delta);
        observation.composed(Duration.ofNanos(System.nanoTime() - composeStart));
        if (composedOpt.isEmpty()) {
            observation.pathSelected(Path.FULL, Reason.GLOBAL_SHAREHOLDER_EFFECT);
            return prepareFull(event, reads);
        }

        var composed = composedOpt.get();
        var changedKeys = composed.changedKeys();
        // D1: Unchanged keys from committed immutable read_pot_version(K) are guaranteed present
        // in immutable read_subobject_version; only changed/new keys require presence checks.
        var presentChangedKeys = reads.effects(c -> subobjects.existingKeys(changedKeys, c));
        var missing = changedKeys.stream().filter(k -> !presentChangedKeys.contains(k)).toList();
        PotVersion contentShell = composed.contentShell();
        if (missing.stream().anyMatch(k -> k.kind() == SubobjectKind.EXPENSE_FINANCIALS)) {
            contentShell = enrichShellWithAllShareholders(previous, eventRef.version(), composed, reads);
        }
        PotVersion finalContentShell = contentShell;
        var loaded = missing.isEmpty()
                ? List.<SubobjectVersion>of()
                : reads.source(c -> source.contents(finalContentShell, missing, c));
        verifyLoadedKeys(changedKeys, presentChangedKeys, loaded);

        long treeStart = System.nanoTime();
        PotVersionWritePlan plan = buildIncrementalWritePlan(previous, eventRef.version(), composed, reads);
        observation.treeBuilt(
                Path.INCREMENTAL,
                Duration.ofNanos(System.nanoTime() - treeStart),
                plan.newNodeCount(),
                plan.nodesLoaded(),
                plan.nodesReused());

        observation.pathSelected(Path.INCREMENTAL, Reason.INCREMENTAL_HIT);
        return buildPrepared(plan, loaded, presentChangedKeys, Path.INCREMENTAL);
    }

    private PotVersion enrichShellWithAllShareholders(
            PotVersionManifest previous,
            long targetVersion,
            PotVersionDeltaComposer.ComposedDelta composed,
            PreparationReads reads) {
        var prevView = reads.effects(
                c -> versions.readShareholders(previous.potId(), previous.version(), c))
                .orElseThrow(() -> new IllegalStateException("Missing reference shareholders at version " + previous.version()));
        Map<UUID, PotVersion.Shareholder> merged = new LinkedHashMap<>();
        for (PotVersion.Shareholder s : prevView.shareholders()) {
            merged.put(s.id(), s);
        }
        merged.putAll(composed.shareholderUpserts());
        return new PotVersion(
                previous.potId(),
                targetVersion,
                composed.nextHeaderVersion(),
                composed.nextDetailsVersion(),
                composed.nextDeleted(),
                List.copyOf(merged.values()),
                List.copyOf(composed.expenseUpserts().values()),
                composed.nextAuthorizedUsers(),
                composed.nextAttachedUsers());
    }

    private PotVersionWritePlan buildIncrementalWritePlan(
            PotVersionManifest previous,
            long targetVersion,
            PotVersionDeltaComposer.ComposedDelta composed,
            PreparationReads reads) {
        Radix16Tree.NodeLoader loader = hashes -> reads.effects(c -> versions.loadNodes(hashes, c));

        Radix16Tree.BuildResult shResult;
        if (composed.shareholderUpserts().isEmpty()) {
            shResult = new Radix16Tree.BuildResult(
                    previous.shareholdersRoot(), Map.of(), 0, previous.shareholdersRoot() == null ? 0 : 1);
        } else {
            Map<UUID, Radix16Codec.EncodedEntry> encodedSh = new HashMap<>(composed.shareholderUpserts().size() * 2);
            for (var e : composed.shareholderUpserts().entrySet()) {
                encodedSh.put(e.getKey(), Radix16Codec.encodeShareholder(e.getValue()));
            }
            shResult = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_SHAREHOLDERS, previous.shareholdersRoot(), encodedSh, Set.of(), loader);
        }

        Radix16Tree.BuildResult exResult;
        if (composed.expenseUpserts().isEmpty()) {
            exResult = new Radix16Tree.BuildResult(
                    previous.expensesRoot(), Map.of(), 0, previous.expensesRoot() == null ? 0 : 1);
        } else {
            Map<UUID, Radix16Codec.EncodedEntry> encodedEx = new HashMap<>(composed.expenseUpserts().size() * 2);
            for (var e : composed.expenseUpserts().entrySet()) {
                encodedEx.put(e.getKey(), Radix16Codec.encodeExpense(e.getValue()));
            }
            exResult = Radix16Tree.applyIncremental(
                    Radix16Codec.TREE_EXPENSES, previous.expensesRoot(), encodedEx, Set.of(), loader);
        }

        List<PotUserSet> userSetsToInsert = new ArrayList<>(2);
        ContentHash nextAuthHash;
        if (composed.nextAuthorizedUsers().equals(previous.authorizedUserIds())) {
            nextAuthHash = previous.authorizedUsersHash();
        } else {
            Optional<PotUserSet> authSet = PotUserSet.of(composed.nextAuthorizedUsers());
            authSet.ifPresent(userSetsToInsert::add);
            nextAuthHash = authSet.map(PotUserSet::hash).orElse(null);
        }

        ContentHash nextAttachedHash;
        if (composed.nextAttachedUsers().equals(previous.attachedActiveUserIds())) {
            nextAttachedHash = previous.attachedActiveUsersHash();
        } else {
            Optional<PotUserSet> attachedSet = PotUserSet.of(composed.nextAttachedUsers());
            attachedSet.ifPresent(userSetsToInsert::add);
            nextAttachedHash = attachedSet.map(PotUserSet::hash).orElse(null);
        }

        List<Radix16Node> newNodes = new ArrayList<>(shResult.newNodes().size() + exResult.newNodes().size());
        newNodes.addAll(shResult.newNodes().values());
        newNodes.addAll(exResult.newNodes().values());
        List<List<Radix16Node>> levels = Radix16Tree.groupBottomUpByDepth(newNodes);

        PotVersionManifest manifest = new PotVersionManifest(
                previous.potId(),
                targetVersion,
                Radix16Codec.SCHEMA_VERSION,
                composed.nextHeaderVersion(),
                composed.nextDetailsVersion(),
                composed.nextDeleted(),
                composed.nextShareholdersCount(),
                composed.nextExpensesCount(),
                shResult.rootHash(),
                exResult.rootHash(),
                nextAuthHash,
                nextAttachedHash,
                composed.nextAuthorizedUsers(),
                composed.nextAttachedUsers());

        return new PotVersionWritePlan(
                manifest,
                userSetsToInsert,
                levels,
                shResult.nodesLoaded() + exResult.nodesLoaded(),
                shResult.nodesReused() + exResult.nodesReused());
    }

    private static boolean isDeltaTooLarge(PotVersionManifest previous, PotVersionSource.StateDelta delta) {
        int changed = delta.changedSubobjectCount();
        if (changed <= MIN_DELTA_VOLUME_FALLBACK) return false;
        int previousTotal = 2 + previous.shareholdersCount() * 3 + previous.expensesCount() * 3;
        return changed * 2 > previousTotal;
    }

    private Optional<Prepared> prepareFull(UUID event, PreparationReads reads) {
        var value = reads.source(c -> source.composition(event, c));
        var allKeys = value.keys();
        var presentKeys = reads.effects(c -> subobjects.existingKeys(allKeys, c));
        var missing = allKeys.stream().filter(k -> !presentKeys.contains(k)).toList();
        var loaded = missing.isEmpty()
                ? List.<SubobjectVersion>of()
                : reads.source(c -> source.contents(value, missing, c));
        verifyLoadedKeys(allKeys, presentKeys, loaded);

        long treeStart = System.nanoTime();
        PotVersionWritePlan plan = PotVersionWritePlan.fromFull(value);
        observation.treeBuilt(
                Path.FULL,
                Duration.ofNanos(System.nanoTime() - treeStart),
                plan.newNodeCount(),
                0,
                0);
        return buildPrepared(plan, loaded, presentKeys, Path.FULL);
    }

    private static void verifyLoadedKeys(
            Collection<SubobjectKey> targetKeys,
            Set<SubobjectKey> presentKeys,
            List<SubobjectVersion> loaded) {
        var expectedKeys = new TreeSet<SubobjectKey>(presentKeys);
        for (var s : loaded) {
            if (!expectedKeys.add(s.key())) throw new IllegalStateException("Duplicate loaded key");
        }
        if (!expectedKeys.equals(new TreeSet<>(targetKeys))) {
            throw new IllegalStateException("Incomplete source contents");
        }
    }

    private Optional<Prepared> buildPrepared(
            PotVersionWritePlan plan,
            List<SubobjectVersion> loaded,
            Set<SubobjectKey> presentKeysToVerify,
            Path path) {
        return Optional.of(
                new Prepared() {
                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        long start = System.nanoTime();
                        subobjects.insertOrVerifyAll(loaded);
                        versions.publishPrepared(plan);
                        completion.complete();
                        versions.closePublication();
                        observation.persistedAndPublished(
                                path, Duration.ofNanos(System.nanoTime() - start));
                    }

                    @Override
                    public void requireCompletionReady() {
                        subobjects.requireStored(loaded);
                        subobjects.requireKeysPresent(presentKeysToVerify);
                        versions.requireManifestStored(plan.manifest());
                    }
                });
    }

    @Override
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.composition(event, c));
        var contents = reads.source(c -> source.contents(expected, expected.keys(), c));
        return c -> {
            var root = versions.evidence(expected, c);
            if (root != ReadEvidence.SATISFIED) return EffectEvidence.valueOf(root.name());
            if (contents.size() != expected.keys().size()) return EffectEvidence.DIVERGENT;
            return EffectEvidence.valueOf(subobjects.evidence(contents, c).name());
        };
    }
}
