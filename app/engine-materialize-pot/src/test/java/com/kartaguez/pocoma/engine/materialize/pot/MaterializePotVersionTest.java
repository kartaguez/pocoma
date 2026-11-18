package com.kartaguez.pocoma.engine.materialize.pot;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.domain.pot.PotDetails;
import com.kartaguez.pocoma.domain.pot.PotHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderDetails;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderWeight;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.*;
import com.kartaguez.pocoma.port.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

class MaterializePotVersionTest {

    private static final UUID POT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SH_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static PotVersion samplePotVersion() {
        return new PotVersion(
                POT_ID,
                2L,
                1L,
                1L,
                false,
                List.of(new PotVersion.Shareholder(SH_ID, 1L, 1L, 1L, false, false)),
                List.of(),
                Set.of(CREATOR_ID),
                Set.of());
    }

    private static List<SubobjectVersion> allSubobjects() {
        var pot = new PotId(POT_ID);
        var sh = new ShareholderId(SH_ID);
        return List.of(
                new SubobjectVersion(
                        new SubobjectKey(POT_ID, SubobjectKind.POT_HEADER, POT_ID, 1L),
                        new PotHeaderContent(new PotHeader(pot, false))),
                new SubobjectVersion(
                        new SubobjectKey(POT_ID, SubobjectKind.POT_DETAILS, POT_ID, 1L),
                        new PotDetailsContent(
                                new PotDetails(pot, new Label("Pot"), new UserId(CREATOR_ID)))),
                new SubobjectVersion(
                        new SubobjectKey(POT_ID, SubobjectKind.SHAREHOLDER_HEADER, SH_ID, 1L),
                        new ShareholderHeaderContent(new ShareholderHeader(pot, sh, false))),
                new SubobjectVersion(
                        new SubobjectKey(POT_ID, SubobjectKind.SHAREHOLDER_DETAILS, SH_ID, 1L),
                        new ShareholderDetailsContent(
                                new ShareholderDetails(
                                        pot, sh, new Name("Alice"), Optional.of(new UserId(CREATOR_ID))))),
                new SubobjectVersion(
                        new SubobjectKey(POT_ID, SubobjectKind.SHAREHOLDER_WEIGHT, SH_ID, 1L),
                        new ShareholderWeightContent(
                                new ShareholderWeight(pot, sh, new Weight(new Fraction(1, 1))))));
    }

    @Test
    void prepareUsesExistingKeysAndSkipsReadAllAndFullRequireStored() {
        var pv = samplePotVersion();
        var subobjectsList = allSubobjects();
        var presentSubset = subobjectsList.subList(0, 2);
        var missingSubset = subobjectsList.subList(2, 5);
        var presentKeysSet =
                presentSubset.stream().map(SubobjectVersion::key).collect(Collectors.toSet());

        var readAllCalls = new ArrayList<Collection<SubobjectKey>>();
        var existingKeysCalls = new ArrayList<Collection<SubobjectKey>>();
        var contentsCalls = new ArrayList<Collection<SubobjectKey>>();
        var inserted = new ArrayList<SubobjectVersion>();
        var requireStoredCalls = new ArrayList<Collection<SubobjectVersion>>();
        var requireKeysPresentCalls = new ArrayList<Collection<SubobjectKey>>();

        PotVersionSource source =
                new PotVersionSource() {
                    @Override
                    public PotVersion composition(UUID eventId, RecoveryReadContext context) {
                        return pv;
                    }

                    @Override
                    public List<SubobjectVersion> contents(
                            PotVersion composition,
                            Collection<SubobjectKey> missing,
                            RecoveryReadContext context) {
                        contentsCalls.add(List.copyOf(missing));
                        var requested = new HashSet<>(missing);
                        return subobjectsList.stream()
                                .filter(s -> requested.contains(s.key()))
                                .toList();
                    }
                };

        SubobjectVersionStore subobjects =
                new SubobjectVersionStore() {
                    @Override
                    public Map<SubobjectKey, SubobjectVersion> readAll(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        readAllCalls.add(keys);
                        throw new AssertionError("prepare() must not call readAll()");
                    }

                    @Override
                    public Set<SubobjectKey> existingKeys(
                            Collection<SubobjectKey> keys, RecoveryReadContext context) {
                        existingKeysCalls.add(List.copyOf(keys));
                        return presentKeysSet;
                    }

                    @Override
                    public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {
                        inserted.addAll(candidates);
                    }

                    @Override
                    public void requireStored(Collection<SubobjectVersion> expected) {
                        requireStoredCalls.add(List.copyOf(expected));
                    }

                    @Override
                    public void requireKeysPresent(Collection<SubobjectKey> expectedKeys) {
                        requireKeysPresentCalls.add(List.copyOf(expectedKeys));
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
                    public void insertAndAdvance(PotVersion value) {}

                    @Override
                    public void requireStored(PotVersion value) {}

                    @Override
                    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
                        return OptionalLong.empty();
                    }

                    @Override
                    public Optional<PotVersion> read(
                            UUID pot, long version, RecoveryReadContext context) {
                        return Optional.empty();
                    }

                    @Override
                    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
                        return ReadEvidence.SATISFIED;
                    }

                    @Override
                    public void closePublication() {}
                };

        PreparationReads reads =
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

        var participant = new MaterializePotVersion(source, subobjects, versions);
        var prepared = participant.prepare(EVENT_ID, reads).orElseThrow();

        assertTrue(readAllCalls.isEmpty());
        assertEquals(1, existingKeysCalls.size());
        assertEquals(1, contentsCalls.size());
        assertEquals(
                missingSubset.stream().map(SubobjectVersion::key).collect(Collectors.toSet()),
                new HashSet<>(contentsCalls.getFirst()));

        prepared.finalizeIn(prepared::requireCompletionReady);

        assertEquals(new HashSet<>(missingSubset), new HashSet<>(inserted));
        assertEquals(1, requireStoredCalls.size());
        assertEquals(new HashSet<>(missingSubset), new HashSet<>(requireStoredCalls.getFirst()));
        assertEquals(1, requireKeysPresentCalls.size());
        assertEquals(presentKeysSet, new HashSet<>(requireKeysPresentCalls.getFirst()));
    }
}
