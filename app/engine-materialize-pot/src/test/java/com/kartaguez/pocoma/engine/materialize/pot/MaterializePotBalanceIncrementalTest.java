package com.kartaguez.pocoma.engine.materialize.pot;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.*;
import com.kartaguez.pocoma.port.projection.PotBalanceStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MaterializePotBalanceIncrementalTest {
    private static final PotBalanceCalculator ORACLE = new PotBalanceCalculator();
    private static final PreparationReads DIRECT_READS =
            new PreparationReads() {
                @Override
                public <T> T source(Function<RecoveryReadContext, T> read) {
                    return read.apply(null);
                }

                @Override
                public <T> T effects(Function<RecoveryReadContext, T> read) {
                    return read.apply(null);
                }
            };

    @Test
    void incrementalNMinusMWeightUpdatesAndMultiStepDeltasMatchOracle() {
        var potId = new PotId(UUID.randomUUID());
        var sh1 = new ShareholderId(new UUID(0, 1));
        var sh2 = new ShareholderId(new UUID(0, 2));
        var sh3 = new ShareholderId(new UUID(0, 3));
        var sh4 = new ShareholderId(new UUID(0, 4));
        var exp1 = new ExpenseId(new UUID(1, 1));
        var exp2 = new ExpenseId(new UUID(1, 2));

        var e1v2 =
                new ExpenseFinancials(
                        potId,
                        exp1,
                        new Amount(new Fraction(90, 1)),
                        sh1,
                        Map.of(sh1, Optional.empty(), sh2, Optional.empty(), sh3, Optional.empty()));
        var e2v3 =
                new ExpenseFinancials(
                        potId,
                        exp2,
                        new Amount(new Fraction(50, 1)),
                        sh2,
                        Map.of(
                                sh1,
                                Optional.empty(),
                                sh3,
                                Optional.of(new Weight(new Fraction(3, 2)))));
        var e1v4 =
                new ExpenseFinancials(
                        potId,
                        exp1,
                        new Amount(new Fraction(120, 1)),
                        sh3,
                        Map.of(sh2, Optional.empty(), sh3, Optional.empty()));

        var states = new TreeMap<Long, PotFinancialState>();
        // v1: initial pot with 3 shareholders, no expenses
        states.put(
                1L,
                state(
                        potId,
                        1L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(2, 1), sh3, w(3, 1)),
                        Set.of(),
                        Map.of()));
        // v2: exp1 created
        states.put(
                2L,
                state(
                        potId,
                        2L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(2, 1), sh3, w(3, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1v2))));
        // v3: exp2 (mixed) created
        states.put(
                3L,
                state(
                        potId,
                        3L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(2, 1), sh3, w(3, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1v2), exp2, new ExpSpec(false, e2v3))));
        // v4: exp1 modified (amount + payer + participants)
        states.put(
                4L,
                state(
                        potId,
                        4L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(2, 1), sh3, w(3, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1v4), exp2, new ExpSpec(false, e2v3))));
        // v5: retroactive weight update (zero expenses changed!)
        states.put(
                5L,
                state(
                        potId,
                        5L,
                        false,
                        Map.of(sh1, w(5, 2), sh2, w(1, 3), sh3, w(7, 4)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1v4), exp2, new ExpSpec(false, e2v3))));
        // v6: add sh4 (D1 reindexing) + delete exp1
        states.put(
                6L,
                state(
                        potId,
                        6L,
                        false,
                        Map.of(sh1, w(5, 2), sh2, w(1, 3), sh3, w(7, 4), sh4, w(2, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(true, e1v4), exp2, new ExpSpec(false, e2v3))));
        // v7: delete sh4 (D4 fallback!)
        states.put(
                7L,
                state(
                        potId,
                        7L,
                        false,
                        Map.of(sh1, w(5, 2), sh2, w(1, 3), sh3, w(7, 4), sh4, w(2, 1)),
                        Set.of(sh4),
                        Map.of(exp1, new ExpSpec(true, e1v4), exp2, new ExpSpec(false, e2v3))));
        // v8: terminal DeletePot (D -> Optional.empty())
        states.put(
                8L,
                state(
                        potId,
                        8L,
                        true,
                        Map.of(sh1, w(5, 2), sh2, w(1, 3), sh3, w(7, 4), sh4, w(2, 1)),
                        Set.of(sh4),
                        Map.of(exp1, new ExpSpec(true, e1v4), exp2, new ExpSpec(false, e2v3))));

        var source = new InMemorySource(states);
        var store = new InMemoryStore();
        var obs = new RecordingObservation();
        var participant =
                new MaterializePotBalance(source, store, MaterializePotBalance.Settings.DEFAULT, obs);

        // Materialize out of order: v1 -> v4 (N-K=3) -> v5 (weight update, 0 expenses) -> v2 (N-K=1)
        // -> v3 (N-K=1) -> v6 (N-K=1, add sh4 + delete exp1) -> v7 (D4 fallback) -> v8 (terminal D)
        execute(participant, source.eventFor(1L));
        assertEquals(Reason.INITIAL_VERSION, obs.lastReason);
        assertEquals(1, source.fullLoadCount.get());

        execute(participant, source.eventFor(4L));
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(Reason.INCREMENTAL_HIT, obs.lastReason);
        assertEquals(3L, obs.lastDistance);
        assertEquals(1, source.fullLoadCount.get());

        execute(participant, source.eventFor(5L));
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(Reason.INCREMENTAL_HIT, obs.lastReason);
        assertEquals(1L, obs.lastDistance);
        assertEquals(0, obs.lastDeltaExpenses); // Weight update touched 0 expenses!
        assertEquals(1, source.fullLoadCount.get()); // Never fell back to full load!

        execute(participant, source.eventFor(2L));
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(1L, obs.lastDistance);

        execute(participant, source.eventFor(3L));
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(1L, obs.lastDistance);

        execute(participant, source.eventFor(6L));
        assertEquals(Path.INCREMENTAL, obs.lastPath);
        assertEquals(1L, obs.lastDistance);
        assertEquals(1, source.fullLoadCount.get());

        // v7: DeleteShareholder -> D4 fallback
        execute(participant, source.eventFor(7L));
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.SHAREHOLDER_DELETED, obs.lastReason);
        assertEquals(2, source.fullLoadCount.get());
        assertTrue(store.aggregates.containsKey(7L), "Fallback must still persist aggregate");

        // v8: terminal DeletePot -> no balance, no aggregate, no full load
        execute(participant, source.eventFor(8L));
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.TERMINAL_DELETED, obs.lastReason);
        assertEquals(2, source.fullLoadCount.get());
        assertFalse(store.balances.containsKey(8L));
        assertFalse(store.aggregates.containsKey(8L));

        // Exact rational equivalence against PotBalanceCalculator oracle for all 8 versions
        for (long v = 1L; v <= 8L; v++) {
            var expected = oracleBalance(states.get(v));
            assertEquals(expected, Optional.ofNullable(store.balances.get(v)), "Mismatch at v" + v);
            var recovery = participant.prepareRecovery(source.eventFor(v), DIRECT_READS);
            assertEquals(
                    ConsumptionParticipant.EffectEvidence.SATISFIED, recovery.observe(null));
        }
    }

    @Test
    void fallbacksForThresholdsAndBucketCapAndNoSilentFallbackOnCorruption() {
        var potId = new PotId(UUID.randomUUID());
        var sh1 = new ShareholderId(new UUID(0, 1));
        var sh2 = new ShareholderId(new UUID(0, 2));
        var sh3 = new ShareholderId(new UUID(0, 3));
        var exp1 = new ExpenseId(new UUID(1, 1));
        var exp2 = new ExpenseId(new UUID(1, 2));

        var e1 =
                new ExpenseFinancials(
                        potId,
                        exp1,
                        new Amount(new Fraction(10, 1)),
                        sh1,
                        Map.of(sh1, Optional.empty(), sh2, Optional.empty()));
        var e2 =
                new ExpenseFinancials(
                        potId,
                        exp2,
                        new Amount(new Fraction(20, 1)),
                        sh2,
                        Map.of(sh2, Optional.empty(), sh3, Optional.empty()));

        var states = new TreeMap<Long, PotFinancialState>();
        states.put(
                1L,
                state(
                        potId,
                        1L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(1, 1), sh3, w(1, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1))));
        states.put(
                2L,
                state(
                        potId,
                        2L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(1, 1), sh3, w(1, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1), exp2, new ExpSpec(false, e2))));
        states.put(
                10L,
                state(
                        potId,
                        10L,
                        false,
                        Map.of(sh1, w(1, 1), sh2, w(1, 1), sh3, w(1, 1)),
                        Set.of(),
                        Map.of(exp1, new ExpSpec(false, e1), exp2, new ExpSpec(false, e2))));

        // 1. maxDeltaDistance = 0 -> INCREMENTAL_DISABLED
        var source = new InMemorySource(states);
        var store = new InMemoryStore();
        var obs = new RecordingObservation();
        var disabled =
                new MaterializePotBalance(
                        source, store, new MaterializePotBalance.Settings(0, 64, 256, 10_000), obs);
        execute(disabled, source.eventFor(2L));
        assertEquals(Reason.INCREMENTAL_DISABLED, obs.lastReason);
        assertEquals(oracleBalance(states.get(2L)).orElseThrow(), store.balances.get(2L));

        // 2. Bucket cap: maxAggregateBuckets = 1 -> v1 (1 bucket) persists aggregate; v2 (2 buckets) triggers BUCKET_CAP and does NOT persist aggregate
        store.clear();
        var capped =
                new MaterializePotBalance(
                        source, store, new MaterializePotBalance.Settings(64, 64, 1, 10_000), obs);
        execute(capped, source.eventFor(1L));
        assertTrue(store.aggregates.containsKey(1L));
        execute(capped, source.eventFor(2L));
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.BUCKET_CAP, obs.lastReason);
        assertEquals(oracleBalance(states.get(2L)).orElseThrow(), store.balances.get(2L));
        assertFalse(
                store.aggregates.containsKey(2L),
                "Aggregate exceeding maxAggregateBuckets must not be persisted");

        // 3. Distance > maxDeltaDistance -> NO_REFERENCE
        var tightDist =
                new MaterializePotBalance(
                        source, store, new MaterializePotBalance.Settings(5, 64, 256, 10_000), obs);
        execute(tightDist, source.eventFor(10L));
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.NO_REFERENCE, obs.lastReason);

        // 4. Changed expenses > maxChangedExpenses -> DELTA_TOO_LARGE
        store.clear();
        var tightDelta =
                new MaterializePotBalance(
                        source, store, new MaterializePotBalance.Settings(64, 0, 256, 10_000), obs);
        execute(tightDelta, source.eventFor(1L));
        execute(tightDelta, source.eventFor(2L));
        assertEquals(Path.FULL, obs.lastPath);
        assertEquals(Reason.DELTA_TOO_LARGE, obs.lastReason);

        // 5. Corruption in aggregate store MUST propagate, NEVER silently fall back
        store.corruptNextLookup = true;
        assertThrows(
                IllegalStateException.class,
                () -> execute(tightDelta, source.eventFor(2L)),
                "Corrupt aggregate must fail explicitly");
        assertEquals(LookupOutcome.CORRUPT, obs.lastLookupOutcome);
    }

    private static void execute(MaterializePotBalance participant, UUID eventId) {
        var prepared = participant.prepare(eventId, DIRECT_READS).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);
    }

    private static Optional<PotBalance> oracleBalance(PotFinancialState state) {
        return ORACLE.calculate(state)
                .map(
                        b -> {
                            var values = new HashMap<UUID, Fraction>();
                            b.values().forEach((k, v) -> values.put(k.value(), v));
                            return new PotBalance(b.potId().value(), b.version(), values);
                        });
    }

    private static Weight w(long n, long d) {
        return new Weight(new Fraction(n, d));
    }

    private record ExpSpec(boolean deleted, ExpenseFinancials financials) {}

    private static PotFinancialState state(
            PotId potId,
            long version,
            boolean potDeleted,
            Map<ShareholderId, Weight> weights,
            Set<ShareholderId> deletedShareholders,
            Map<ExpenseId, ExpSpec> expenses) {
        var shMap = new LinkedHashMap<ShareholderId, PotFinancialState.Shareholder>();
        weights.forEach(
                (id, wt) ->
                        shMap.put(
                                id,
                                new PotFinancialState.Shareholder(
                                        rev(new ShareholderHeader(potId, id, deletedShareholders.contains(id))),
                                        rev(new ShareholderWeight(potId, id, wt)))));
        var expMap = new LinkedHashMap<ExpenseId, PotFinancialState.Expense>();
        expenses.forEach(
                (id, spec) ->
                        expMap.put(
                                id,
                                new PotFinancialState.Expense(
                                        rev(new ExpenseHeader(potId, id, spec.deleted())),
                                        rev(spec.financials()))));
        return new PotFinancialState(
                potId, version, rev(new PotHeader(potId, potDeleted)), shMap, expMap);
    }

    private static <T> Versioned<T> rev(T value) {
        return new Versioned<>(1L, 1L, value);
    }

    private static final class InMemorySource implements PotBalanceSource {
        private final NavigableMap<Long, PotFinancialState> states;
        private final Map<Long, UUID> eventsByVersion = new HashMap<>();
        private final Map<UUID, Long> versionsByEvent = new HashMap<>();
        private final AtomicInteger fullLoadCount = new AtomicInteger();

        private InMemorySource(NavigableMap<Long, PotFinancialState> states) {
            this.states = states;
            for (long v : states.keySet()) {
                UUID eventId = UUID.randomUUID();
                eventsByVersion.put(v, eventId);
                versionsByEvent.put(eventId, v);
            }
        }

        private UUID eventFor(long version) {
            return eventsByVersion.get(version);
        }

        @Override
        public PotFinancialState load(UUID eventId, RecoveryReadContext context) {
            fullLoadCount.incrementAndGet();
            return states.get(versionsByEvent.get(eventId));
        }

        @Override
        public EventRef eventRef(UUID eventId, RecoveryReadContext context) {
            var s = states.get(versionsByEvent.get(eventId));
            return new EventRef(s.potId().value(), s.version(), s.header().value().deleted());
        }

        @Override
        public FinancialDelta financialDelta(
                UUID potId,
                long k,
                long n,
                int maxChangedExpenses,
                RecoveryReadContext context) {
            var sk = states.get(k);
            var sn = states.get(n);
            for (var entry : sn.shareholders().entrySet()) {
                boolean delN = entry.getValue().header().value().deleted();
                boolean delK =
                        sk.shareholders().containsKey(entry.getKey())
                                && sk.shareholders().get(entry.getKey()).header().value().deleted();
                if (delN && !delK) {
                    return new FinancialDelta(true, 0, Map.of(), List.of(), List.of());
                }
            }
            var weights = new HashMap<UUID, Weight>();
            for (var entry : sn.shareholders().entrySet()) {
                var wN = entry.getValue().weight().value().defaultWeight();
                var shK = sk.shareholders().get(entry.getKey());
                if (shK == null || !shK.weight().value().defaultWeight().equals(wN)) {
                    weights.put(entry.getKey().value(), wN);
                }
            }
            var removed = new ArrayList<ExpenseFinancials>();
            var added = new ArrayList<ExpenseFinancials>();
            int changed = 0;
            var allExpIds = new HashSet<ExpenseId>();
            allExpIds.addAll(sk.expenses().keySet());
            allExpIds.addAll(sn.expenses().keySet());
            for (var id : allExpIds) {
                var ek = sk.expenses().get(id);
                var en = sn.expenses().get(id);
                boolean activeK = ek != null && !ek.header().value().deleted();
                boolean activeN = en != null && !en.header().value().deleted();
                boolean sameFin =
                        activeK
                                && activeN
                                && ek.financials().value().equals(en.financials().value());
                if (activeK == activeN && (!activeK || sameFin)) {
                    continue;
                }
                changed++;
                if (activeK) removed.add(ek.financials().value());
                if (activeN) added.add(en.financials().value());
            }
            if (changed > maxChangedExpenses) {
                return new FinancialDelta(false, changed, weights, List.of(), List.of());
            }
            return new FinancialDelta(false, changed, weights, removed, added);
        }
    }

    private static final class InMemoryStore implements PotBalanceStore {
        private final TreeMap<Long, PotBalance> balances = new TreeMap<>();
        private final TreeMap<Long, PotBalanceAggregate> aggregates = new TreeMap<>();
        private boolean corruptNextLookup;

        private void clear() {
            balances.clear();
            aggregates.clear();
            corruptNextLookup = false;
        }

        @Override
        public void insertOrVerify(PotBalance balance) {
            var existing = balances.putIfAbsent(balance.version(), balance);
            if (existing != null && !existing.equals(balance)) {
                throw new IllegalStateException("Divergent PotBalance");
            }
        }

        @Override
        public void requireStored(UUID pot, long version, Optional<PotBalance> expected) {
            assertEquals(expected, Optional.ofNullable(balances.get(version)));
        }

        @Override
        public Optional<PotBalance> read(UUID pot, long version, RecoveryReadContext context) {
            return Optional.ofNullable(balances.get(version));
        }

        @Override
        public ReadEvidence evidence(
                UUID pot, long version, Optional<PotBalance> expected, RecoveryReadContext context) {
            var actual = Optional.ofNullable(balances.get(version));
            if (expected.equals(actual)) return ReadEvidence.SATISFIED;
            return actual.isEmpty() ? ReadEvidence.MISSING : ReadEvidence.DIVERGENT;
        }

        @Override
        public Optional<PotBalanceAggregate> readLatestAggregateBefore(
                UUID pot,
                long beforeVersionExclusive,
                long minVersionInclusive,
                RecoveryReadContext context) {
            if (corruptNextLookup) {
                corruptNextLookup = false;
                throw new IllegalStateException("Corrupt PotBalanceAggregate");
            }
            var entry = aggregates.lowerEntry(beforeVersionExclusive);
            if (entry == null || entry.getKey() < minVersionInclusive) {
                return Optional.empty();
            }
            return Optional.of(entry.getValue());
        }

        @Override
        public void insertOrVerifyAggregate(PotBalanceAggregate aggregate) {
            if (!balances.containsKey(aggregate.version())) {
                throw new IllegalStateException("FK violation: balance missing for aggregate");
            }
            var existing = aggregates.putIfAbsent(aggregate.version(), aggregate);
            if (existing != null && !existing.equals(aggregate)) {
                throw new IllegalStateException("Divergent PotBalanceAggregate");
            }
        }

        @Override
        public void requireAggregateStored(PotBalanceAggregate expected) {
            assertEquals(expected, aggregates.get(expected.version()));
        }

        @Override
        public void closePublication() {}

        @Override
        public void requireHashesStored(
                UUID pot,
                long version,
                Optional<ContentHash> expectedBalanceHash,
                Optional<ContentHash> expectedAggregateHash) {
            var bal = balances.get(version);
            var agg = aggregates.get(version);
            if (expectedBalanceHash.isEmpty()) {
                assertNull(bal, "Unexpected balance at deleted version " + version);
                assertNull(agg, "Unexpected aggregate at deleted version " + version);
                return;
            }
            assertNotNull(bal, "Balance missing at version " + version);
            assertEquals(
                    expectedBalanceHash.get(),
                    PotBalanceBinaryCodec.encode(bal).content().hash());
            if (expectedAggregateHash.isEmpty()) {
                assertNull(agg, "Unexpected aggregate at version " + version);
            } else {
                assertNotNull(agg, "Aggregate missing at version " + version);
                assertEquals(
                        expectedAggregateHash.get(),
                        PotBalanceAggregateBinaryCodec.encode(agg).content().hash());
            }
        }
    }

    private static final class RecordingObservation implements PotBalanceObservation {
        LookupOutcome lastLookupOutcome;
        long lastDistance;
        int lastDeltaExpenses;
        Path lastPath;
        Reason lastReason;

        @Override
        public void aggregateLookup(LookupOutcome outcome, long distance, Duration duration) {
            this.lastLookupOutcome = outcome;
            this.lastDistance = distance;
        }

        @Override
        public void deltaResolved(int changedExpenses, Duration duration) {
            this.lastDeltaExpenses = changedExpenses;
        }

        @Override
        public void aggregateComputed(int bucketCount, int bucketEntryCount, Duration duration) {}

        @Override
        public void balanceEvaluated(Path path, Duration duration) {}

        @Override
        public void persisted(PersistTarget target, Duration duration) {}

        @Override
        public void pathSelected(Path path, Reason reason) {
            this.lastPath = path;
            this.lastReason = reason;
        }
    }
}
