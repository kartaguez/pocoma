package com.kartaguez.pocoma.engine.materialize.pot;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.domain.pot.PotDetails;
import com.kartaguez.pocoma.domain.pot.PotHeader;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderDetails;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderWeight;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.*;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionObservation.*;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionSource.*;
import com.kartaguez.pocoma.port.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MaterializePotVersionIncrementalTest {
    private static final UUID POT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID USER_2 = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID SH_1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SH_2 = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EXP_1 = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID EXP_2 = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID EVENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private static final PreparationReads READS =
            new PreparationReads() {
                @Override
                public <T> T source(Function<RecoveryReadContext, T> action) {
                    return action.apply(() -> {});
                }

                @Override
                public <T> T effects(Function<RecoveryReadContext, T> action) {
                    return action.apply(() -> {});
                }
            };

    private static final class RecordingObservation implements PotVersionObservation {
        LookupOutcome lastOutcome;
        long lastDistance;
        int lastDeltaCount;
        Path lastPath;
        Reason lastReason;
        boolean persistedRecorded;

        @Override
        public void referenceLookup(LookupOutcome outcome, long distance, Duration duration) {
            this.lastOutcome = outcome;
            this.lastDistance = distance;
        }

        @Override
        public void deltaResolved(int changedSubobjects, Duration duration) {
            this.lastDeltaCount = changedSubobjects;
        }

        @Override
        public void composed(Duration duration) {}

        @Override
        public void pathSelected(Path path, Reason reason) {
            this.lastPath = path;
            this.lastReason = reason;
        }

        @Override
        public void persistedAndPublished(Path path, Duration duration) {
            this.persistedRecorded = true;
        }
    }

    private static PotVersion versionK(long k) {
        return new PotVersion(
                POT_ID,
                k,
                1L,
                1L,
                false,
                List.of(
                        new PotVersion.Shareholder(SH_1, 1L, 1L, 1L, false, false),
                        new PotVersion.Shareholder(SH_2, 1L, 1L, 1L, false, true)),
                List.of(new PotVersion.Expense(EXP_1, 1L, 1L, 1L, false)),
                Set.of(CREATOR_ID),
                Set.of(CREATOR_ID));
    }

    private static SubobjectVersion subobject(SubobjectKey key) {
        var pot = new PotId(POT_ID);
        return switch (key.kind()) {
            case POT_HEADER -> new SubobjectVersion(key, new PotHeaderContent(new PotHeader(pot, false)));
            case POT_DETAILS ->
                    new SubobjectVersion(
                            key, new PotDetailsContent(new PotDetails(pot, new Label("L"), new UserId(CREATOR_ID))));
            case SHAREHOLDER_HEADER ->
                    new SubobjectVersion(
                            key,
                            new ShareholderHeaderContent(
                                    new ShareholderHeader(pot, new ShareholderId(key.objectId()), false)));
            case SHAREHOLDER_DETAILS ->
                    new SubobjectVersion(
                            key,
                            new ShareholderDetailsContent(
                                    new ShareholderDetails(
                                            pot,
                                            new ShareholderId(key.objectId()),
                                            new Name("N"),
                                            key.objectId().equals(SH_2)
                                                    ? Optional.of(new UserId(USER_2))
                                                    : Optional.of(new UserId(CREATOR_ID)))));
            case SHAREHOLDER_WEIGHT ->
                    new SubobjectVersion(
                            key,
                            new ShareholderWeightContent(
                                    new ShareholderWeight(
                                            pot, new ShareholderId(key.objectId()), new Weight(new Fraction(2, 1)))));
            case EXPENSE_HEADER ->
                    new SubobjectVersion(
                            key,
                            new ExpenseHeaderContent(
                                    new ExpenseHeader(
                                            pot, new ExpenseId(key.objectId()), key.localVersion() > 1)));
            case EXPENSE_DETAILS ->
                    new SubobjectVersion(
                            key,
                            new ExpenseDetailsContent(
                                    new ExpenseDetails(
                                            pot,
                                            new ExpenseId(key.objectId()),
                                            new Label("E"),
                                            LocalDate.of(2026, 1, 1))));
            case EXPENSE_FINANCIALS ->
                    new SubobjectVersion(
                            key,
                            new ExpenseFinancialsContent(
                                    new ExpenseFinancials(
                                            pot,
                                            new ExpenseId(key.objectId()),
                                            new Amount(new Fraction(10, 1)),
                                            new ShareholderId(SH_1),
                                            Map.of(new ShareholderId(SH_1), Optional.empty()))));
        };
    }

    @Test
    void incrementalFromNMinusMComposesMultiObjectChangesAndSkipsFullComposition() {
        PotVersion refK = versionK(3L);
        long targetN = 8L; // m = 5
        StateDelta delta =
                new StateDelta(
                        Optional.empty(),
                        Optional.of(new PotDetailsChange(2L, CREATOR_ID)),
                        Map.of(),
                        Map.of(SH_2, new ShareholderDetailsChange(SH_2, 2L, USER_2)),
                        Map.of(SH_1, new ShareholderWeightChange(SH_1, 3L)),
                        Map.of(
                                EXP_1, new ExpenseHeaderChange(EXP_1, 2L, true),
                                EXP_2, new ExpenseHeaderChange(EXP_2, 1L, false)),
                        Map.of(EXP_2, new ExpenseDetailsChange(EXP_2, 1L)),
                        Map.of(EXP_2, new ExpenseFinancialsChange(EXP_2, 1L)));

        PotVersion expectedOracle =
                new PotVersion(
                        POT_ID,
                        targetN,
                        1L,
                        2L,
                        false,
                        List.of(
                                new PotVersion.Shareholder(SH_1, 1L, 1L, 3L, false, false),
                                new PotVersion.Shareholder(SH_2, 1L, 2L, 1L, false, false)),
                        List.of(
                                new PotVersion.Expense(EXP_1, 2L, 1L, 1L, true),
                                new PotVersion.Expense(EXP_2, 1L, 1L, 1L, false)),
                        Set.of(CREATOR_ID, USER_2),
                        Set.of(CREATOR_ID, USER_2));

        var existingKeysQueries = new ArrayList<Collection<SubobjectKey>>();
        var requireKeysPresentQueries = new ArrayList<Collection<SubobjectKey>>();
        var storedVersion = new ArrayList<PotVersion>();
        var obs = new RecordingObservation();

        PotVersionSource source =
                new PotVersionSource() {
                    @Override
                    public EventRef eventRef(UUID eventId, RecoveryReadContext context) {
                        return new EventRef(POT_ID, targetN);
                    }

                    @Override
                    public StateDelta stateDelta(
                            UUID potId, long afterExclusive, long targetInclusive, RecoveryReadContext context) {
                        assertEquals(3L, afterExclusive);
                        assertEquals(8L, targetInclusive);
                        return delta;
                    }

                    @Override
                    public PotVersion composition(UUID eventId, RecoveryReadContext context) {
                        throw new AssertionError("Full composition must not be called on incremental hit");
                    }

                    @Override
                    public List<SubobjectVersion> contents(
                            PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context) {
                        return missing.stream().map(MaterializePotVersionIncrementalTest::subobject).toList();
                    }
                };

        SubobjectVersionStore subobjects =
                new SubobjectVersionStore() {
                    @Override
                    public Map<SubobjectKey, SubobjectVersion> readAll(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        throw new AssertionError("readAll must not be called");
                    }

                    @Override
                    public Set<SubobjectKey> existingKeys(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        existingKeysQueries.add(List.copyOf(keys));
                        return Set.of();
                    }

                    @Override
                    public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {}

                    @Override
                    public void requireStored(Collection<SubobjectVersion> expected) {}

                    @Override
                    public void requireKeysPresent(Collection<SubobjectKey> expectedKeys) {
                        requireKeysPresentQueries.add(List.copyOf(expectedKeys));
                    }

                    @Override
                    public ReadEvidence evidence(
                            Collection<SubobjectVersion> expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }
                };

        PotVersionStore versions =
                new PotVersionStore() {
                    @Override
                    public void insertAndAdvance(PotVersion value) {
                        storedVersion.add(value);
                    }

                    @Override
                    public void requireStored(PotVersion value) {}

                    @Override
                    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
                        return OptionalLong.of(3L);
                    }

                    @Override
                    public Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context) {
                        return version == 3L ? Optional.of(refK) : Optional.empty();
                    }

                    @Override
                    public Optional<PotVersion> readLatestBefore(
                            UUID pot, long beforeExclusive, long minInclusive, RecoveryReadContext context) {
                        assertEquals(8L, beforeExclusive);
                        assertEquals(1L, minInclusive);
                        return Optional.of(refK);
                    }

                    @Override
                    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }

                    @Override
                    public void closePublication() {}
                };

        var participant = new MaterializePotVersion(source, subobjects, versions, 64L, obs);
        var prepared = participant.prepare(EVENT_ID, READS).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);

        assertEquals(List.of(expectedOracle), storedVersion);
        assertEquals(versionK(3L), refK); // Immutability of K
        assertEquals(LookupOutcome.HIT, obs.lastOutcome);
        assertEquals(5L, obs.lastDistance);
        assertEquals(7, obs.lastDeltaCount);
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(Reason.INCREMENTAL_HIT, obs.lastReason);
        assertTrue(obs.persistedRecorded);
        // D1: Only the 7 changed keys checked in prepare, and 0 unchanged keys re-checked in finalize
        assertEquals(1, existingKeysQueries.size());
        assertEquals(7, existingKeysQueries.getFirst().size());
        assertEquals(1, requireKeysPresentQueries.size());
        assertTrue(requireKeysPresentQueries.getFirst().isEmpty());
    }

    @Test
    void globalShareholderEffectFallsBackImmediatelyToFullPath() {
        PotVersion refK = versionK(3L);
        PotVersion expectedFull =
                new PotVersion(
                        POT_ID,
                        4L,
                        1L,
                        1L,
                        false,
                        List.of(
                                new PotVersion.Shareholder(SH_1, 1L, 2L, 1L, false, true),
                                new PotVersion.Shareholder(SH_2, 1L, 1L, 1L, false, true)),
                        List.of(new PotVersion.Expense(EXP_1, 1L, 1L, 1L, false)),
                        Set.of(CREATOR_ID),
                        Set.of());
        StateDelta detachDelta =
                new StateDelta(
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        Map.of(SH_1, new ShareholderDetailsChange(SH_1, 2L, null)),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());

        var fullCalls = new ArrayList<UUID>();
        var obs = new RecordingObservation();

        PotVersionSource source =
                new PotVersionSource() {
                    @Override
                    public EventRef eventRef(UUID eventId, RecoveryReadContext context) {
                        return new EventRef(POT_ID, 4L);
                    }

                    @Override
                    public StateDelta stateDelta(
                            UUID potId, long afterExclusive, long targetInclusive, RecoveryReadContext context) {
                        return detachDelta;
                    }

                    @Override
                    public PotVersion composition(UUID eventId, RecoveryReadContext context) {
                        fullCalls.add(eventId);
                        return expectedFull;
                    }

                    @Override
                    public List<SubobjectVersion> contents(
                            PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context) {
                        return missing.stream().map(MaterializePotVersionIncrementalTest::subobject).toList();
                    }
                };

        SubobjectVersionStore subobjects =
                new SubobjectVersionStore() {
                    @Override
                    public Map<SubobjectKey, SubobjectVersion> readAll(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        throw new AssertionError();
                    }

                    @Override
                    public Set<SubobjectKey> existingKeys(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        return new HashSet<>(keys);
                    }

                    @Override
                    public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {}

                    @Override
                    public void requireStored(Collection<SubobjectVersion> expected) {}

                    @Override
                    public void requireKeysPresent(Collection<SubobjectKey> expectedKeys) {}

                    @Override
                    public ReadEvidence evidence(
                            Collection<SubobjectVersion> expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }
                };

        PotVersionStore versions =
                new PotVersionStore() {
                    @Override
                    public void insertAndAdvance(PotVersion value) {
                        assertEquals(expectedFull, value);
                    }

                    @Override
                    public void requireStored(PotVersion value) {}

                    @Override
                    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
                        return OptionalLong.of(3L);
                    }

                    @Override
                    public Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context) {
                        return Optional.of(refK);
                    }

                    @Override
                    public Optional<PotVersion> readLatestBefore(
                            UUID pot, long beforeExclusive, long minInclusive, RecoveryReadContext context) {
                        return Optional.of(refK);
                    }

                    @Override
                    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }

                    @Override
                    public void closePublication() {}
                };

        var participant = new MaterializePotVersion(source, subobjects, versions, 64L, obs);
        var prepared = participant.prepare(EVENT_ID, READS).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);

        assertEquals(List.of(EVENT_ID), fullCalls);
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.GLOBAL_SHAREHOLDER_EFFECT, obs.lastReason);
    }

    @Test
    void corruptReferencePropagatesAndNeverFallsBackSilently() {
        var obs = new RecordingObservation();
        PotVersionSource source =
                new PotVersionSource() {
                    @Override
                    public EventRef eventRef(UUID eventId, RecoveryReadContext context) {
                        return new EventRef(POT_ID, 5L);
                    }

                    @Override
                    public StateDelta stateDelta(
                            UUID potId, long afterExclusive, long targetInclusive, RecoveryReadContext context) {
                        throw new AssertionError("Must not be called on corrupt reference");
                    }

                    @Override
                    public PotVersion composition(UUID eventId, RecoveryReadContext context) {
                        throw new AssertionError("Must not fall back silently on corrupt reference");
                    }

                    @Override
                    public List<SubobjectVersion> contents(
                            PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context) {
                        throw new AssertionError();
                    }
                };

        SubobjectVersionStore subobjects =
                new SubobjectVersionStore() {
                    @Override
                    public Map<SubobjectKey, SubobjectVersion> readAll(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        throw new AssertionError();
                    }

                    @Override
                    public Set<SubobjectKey> existingKeys(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        throw new AssertionError();
                    }

                    @Override
                    public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {}

                    @Override
                    public void requireStored(Collection<SubobjectVersion> expected) {}

                    @Override
                    public void requireKeysPresent(Collection<SubobjectKey> expectedKeys) {}

                    @Override
                    public ReadEvidence evidence(
                            Collection<SubobjectVersion> expected, RecoveryReadContext context) {
                        return ReadEvidence.DIVERGENT;
                    }
                };

        PotVersionStore versions =
                new PotVersionStore() {
                    @Override
                    public void insertAndAdvance(PotVersion value) {}

                    @Override
                    public void requireStored(PotVersion value) {}

                    @Override
                    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
                        return OptionalLong.empty();
                    }

                    @Override
                    public Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<PotVersion> readLatestBefore(
                            UUID pot, long beforeExclusive, long minInclusive, RecoveryReadContext context) {
                        throw new IllegalStateException("Corrupt PotVersion");
                    }

                    @Override
                    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
                        return ReadEvidence.DIVERGENT;
                    }

                    @Override
                    public void closePublication() {}
                };

        var participant = new MaterializePotVersion(source, subobjects, versions, 64L, obs);
        var ex = assertThrows(IllegalStateException.class, () -> participant.prepare(EVENT_ID, READS));
        assertEquals("Corrupt PotVersion", ex.getMessage());
        assertEquals(LookupOutcome.CORRUPT, obs.lastOutcome);
        assertNull(obs.lastPath);
    }

    @Test
    void maxDeltaDistanceWindowEnforcedInStoreQuery() {
        var capturedBounds = new ArrayList<long[]>();
        var obs = new RecordingObservation();
        PotVersion expectedFull = versionK(100L);

        PotVersionSource source =
                new PotVersionSource() {
                    @Override
                    public EventRef eventRef(UUID eventId, RecoveryReadContext context) {
                        return new EventRef(POT_ID, 100L);
                    }

                    @Override
                    public StateDelta stateDelta(
                            UUID potId, long afterExclusive, long targetInclusive, RecoveryReadContext context) {
                        throw new AssertionError();
                    }

                    @Override
                    public PotVersion composition(UUID eventId, RecoveryReadContext context) {
                        return expectedFull;
                    }

                    @Override
                    public List<SubobjectVersion> contents(
                            PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context) {
                        return List.of();
                    }
                };

        SubobjectVersionStore subobjects =
                new SubobjectVersionStore() {
                    @Override
                    public Map<SubobjectKey, SubobjectVersion> readAll(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        throw new AssertionError();
                    }

                    @Override
                    public Set<SubobjectKey> existingKeys(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        return new HashSet<>(keys);
                    }

                    @Override
                    public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {}

                    @Override
                    public void requireStored(Collection<SubobjectVersion> expected) {}

                    @Override
                    public void requireKeysPresent(Collection<SubobjectKey> expectedKeys) {}

                    @Override
                    public ReadEvidence evidence(
                            Collection<SubobjectVersion> expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }
                };

        PotVersionStore versions =
                new PotVersionStore() {
                    @Override
                    public void insertAndAdvance(PotVersion value) {}

                    @Override
                    public void requireStored(PotVersion value) {}

                    @Override
                    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
                        return OptionalLong.empty();
                    }

                    @Override
                    public Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<PotVersion> readLatestBefore(
                            UUID pot, long beforeExclusive, long minInclusive, RecoveryReadContext context) {
                        capturedBounds.add(new long[] {beforeExclusive, minInclusive});
                        return Optional.empty();
                    }

                    @Override
                    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }

                    @Override
                    public void closePublication() {}
                };

        var participant = new MaterializePotVersion(source, subobjects, versions, 64L, obs);
        participant.prepare(EVENT_ID, READS);

        assertEquals(1, capturedBounds.size());
        assertArrayEquals(new long[] {100L, 36L}, capturedBounds.getFirst());
        assertEquals(LookupOutcome.MISS, obs.lastOutcome);
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.NO_REFERENCE, obs.lastReason);
    }
}
