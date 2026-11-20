package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.domain.pot.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregateCalculator;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.ContentOutcome;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.LookupOutcome;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.Path;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.PersistTarget;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation.Reason;
import com.kartaguez.pocoma.port.projection.PotBalanceStore;
import com.kartaguez.pocoma.port.projection.PotBalanceStore.AggregateRef;
import com.kartaguez.pocoma.port.projection.PotBalanceStore.ContentWriteOutcome;
import java.time.Duration;
import java.util.*;

public final class MaterializePotBalance implements ConsumptionParticipant {
    public static final long DEFAULT_MAX_DELTA_DISTANCE = 64L;
    public static final int DEFAULT_MAX_CHANGED_EXPENSES = 64;
    public static final int DEFAULT_MAX_AGGREGATE_BUCKETS = 256;
    public static final int DEFAULT_MAX_AGGREGATE_BUCKET_ENTRIES = 10_000;

    public record Settings(
            long maxDeltaDistance,
            int maxChangedExpenses,
            int maxAggregateBuckets,
            int maxAggregateBucketEntries,
            boolean emptyDeltaReuseEnabled) {
        public static final Settings DEFAULT =
                new Settings(
                        DEFAULT_MAX_DELTA_DISTANCE,
                        DEFAULT_MAX_CHANGED_EXPENSES,
                        DEFAULT_MAX_AGGREGATE_BUCKETS,
                        DEFAULT_MAX_AGGREGATE_BUCKET_ENTRIES,
                        true);

        public Settings(
                long maxDeltaDistance,
                int maxChangedExpenses,
                int maxAggregateBuckets,
                int maxAggregateBucketEntries) {
            this(
                    maxDeltaDistance,
                    maxChangedExpenses,
                    maxAggregateBuckets,
                    maxAggregateBucketEntries,
                    true);
        }

        public Settings {
            if (maxDeltaDistance < 0) {
                throw new IllegalArgumentException("Non-negative maxDeltaDistance required");
            }
            if (maxChangedExpenses < 0) {
                throw new IllegalArgumentException("Non-negative maxChangedExpenses required");
            }
            if (maxAggregateBuckets < 0) {
                throw new IllegalArgumentException("Non-negative maxAggregateBuckets required");
            }
            if (maxAggregateBucketEntries < 0) {
                throw new IllegalArgumentException(
                        "Non-negative maxAggregateBucketEntries required");
            }
        }
    }

    private final PotBalanceSource source;
    private final PotBalanceStore store;
    private final Settings settings;
    private final PotBalanceObservation observation;
    private final PotBalanceCalculator fullCalculator = new PotBalanceCalculator();
    private final PotBalanceAggregateCalculator aggregateCalculator =
            new PotBalanceAggregateCalculator();

    public MaterializePotBalance(PotBalanceSource source, PotBalanceStore store) {
        this(source, store, Settings.DEFAULT, PotBalanceObservation.NOOP);
    }

    public MaterializePotBalance(
            PotBalanceSource source,
            PotBalanceStore store,
            Settings settings,
            PotBalanceObservation observation) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
        this.settings = Objects.requireNonNull(settings);
        this.observation = Objects.requireNonNull(observation);
    }

    private Optional<PotBalance> calculateFull(PotFinancialState state) {
        return fullCalculator
                .calculate(state)
                .map(
                        b -> {
                            var values = new HashMap<UUID, Fraction>(b.values().size() * 2);
                            b.values().forEach((k, v) -> values.put(k.value(), v));
                            return new PotBalance(b.potId().value(), b.version(), values);
                        });
    }

    private boolean isWithinAggregateLimits(PotBalanceAggregate aggregate) {
        return aggregate.bucketCount() <= settings.maxAggregateBuckets()
                && aggregate.bucketEntryCount() <= settings.maxAggregateBucketEntries();
    }

    @Override
    public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
        var eventRef = reads.source(c -> source.eventRef(id, c));
        if (eventRef.potDeleted()) {
            observation.pathSelected(Path.FULL, Reason.TERMINAL_DELETED);
            return buildPrepared(
                    eventRef.potId(), eventRef.version(), Optional.empty(), Optional.empty());
        }
        if (settings.maxDeltaDistance() == 0) {
            observation.pathSelected(Path.FULL, Reason.INCREMENTAL_DISABLED);
            return prepareFull(id, reads);
        }
        if (eventRef.version() == 1) {
            observation.pathSelected(Path.FULL, Reason.INITIAL_VERSION);
            return prepareFull(id, reads);
        }
        var refOpt = lookupReferenceRef(eventRef, reads);
        if (refOpt.isEmpty()) {
            observation.pathSelected(Path.FULL, Reason.NO_REFERENCE);
            return prepareFull(id, reads);
        }
        return prepareReuseIncrementalOrFallback(id, eventRef, refOpt.get(), reads);
    }

    private Optional<AggregateRef> lookupReferenceRef(
            PotBalanceSource.EventRef eventRef, PreparationReads reads) {
        long minVersion = Math.max(1L, eventRef.version() - settings.maxDeltaDistance());
        long start = System.nanoTime();
        final Optional<AggregateRef> refOpt;
        try {
            refOpt =
                    reads.effects(
                            c ->
                                    store.readLatestAggregateRefBefore(
                                            eventRef.potId(),
                                            eventRef.version(),
                                            minVersion,
                                            c));
        } catch (RuntimeException | Error fault) {
            if (fault instanceof IllegalStateException) {
                observation.aggregateLookup(
                        LookupOutcome.CORRUPT, 0L, Duration.ofNanos(System.nanoTime() - start));
            }
            throw fault;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (refOpt.isEmpty()) {
            observation.aggregateLookup(LookupOutcome.MISS, 0L, elapsed);
            return Optional.empty();
        }
        AggregateRef ref = refOpt.get();
        long distance = eventRef.version() - ref.version();
        if (distance < 1 || distance > settings.maxDeltaDistance()) {
            observation.aggregateLookup(LookupOutcome.CORRUPT, 0L, elapsed);
            throw new IllegalStateException("Incompatible reference PotBalanceAggregateRef");
        }
        observation.aggregateLookup(LookupOutcome.HIT, distance, elapsed);
        return Optional.of(ref);
    }

    /**
     * Formal ARB-3 empty-delta identity proof on {@code (K, N]}:
     * all financial revision tables ({@code v3_shareholder_header} deletions,
     * {@code v3_shareholder_weight}, {@code v3_expense_header}, {@code v3_expense_financials})
     * had zero revisions becoming active in {@code (K, N]}. Any intermediate change or
     * change-and-revert in {@code (K, N]} produces a revision with {@code active_from in (K, N]}
     * and therefore fails this predicate, falling back to normal A4 incremental calculation.
     */
    private static boolean isProvenEmptyFinancialDelta(PotBalanceSource.FinancialDelta delta) {
        return !delta.shareholderDeleted()
                && delta.updatedOrAddedWeights().isEmpty()
                && delta.changedExpenseCount() == 0
                && delta.removedExpenses().isEmpty()
                && delta.addedExpenses().isEmpty();
    }

    private Optional<Prepared> prepareReuseIncrementalOrFallback(
            UUID eventId,
            PotBalanceSource.EventRef eventRef,
            AggregateRef ref,
            PreparationReads reads) {
        long deltaStart = System.nanoTime();
        var delta =
                reads.source(
                        c ->
                                source.financialDelta(
                                        eventRef.potId(),
                                        ref.version(),
                                        eventRef.version(),
                                        settings.maxChangedExpenses(),
                                        c));
        observation.deltaResolved(
                delta.changedExpenseCount(), Duration.ofNanos(System.nanoTime() - deltaStart));
        if (settings.emptyDeltaReuseEnabled() && isProvenEmptyFinancialDelta(delta)) {
            observation.pathSelected(Path.REUSE, Reason.EMPTY_DELTA_REUSE);
            return buildReusePrepared(
                    eventRef.potId(), eventRef.version(), ref.balanceHash(), ref.aggregateHash());
        }
        if (delta.shareholderDeleted()) {
            observation.pathSelected(Path.FULL, Reason.SHAREHOLDER_DELETED);
            return prepareFull(eventId, reads);
        }
        if (delta.changedExpenseCount() > settings.maxChangedExpenses()) {
            observation.pathSelected(Path.FULL, Reason.DELTA_TOO_LARGE);
            return prepareFull(eventId, reads);
        }
        PotBalanceAggregate previous = loadAggregatePayload(eventRef.potId(), ref, reads);
        long composeStart = System.nanoTime();
        PotBalanceAggregate composed =
                aggregateCalculator.applyDelta(
                        previous,
                        eventRef.version(),
                        delta.updatedOrAddedWeights(),
                        delta.removedExpenses(),
                        delta.addedExpenses());
        observation.aggregateComputed(
                composed.bucketCount(),
                composed.bucketEntryCount(),
                Duration.ofNanos(System.nanoTime() - composeStart));
        if (!isWithinAggregateLimits(composed)) {
            observation.pathSelected(Path.FULL, Reason.BUCKET_CAP);
            return prepareFull(eventId, reads);
        }
        long evalStart = System.nanoTime();
        PotBalance balance = aggregateCalculator.calculate(composed);
        observation.balanceEvaluated(Path.INCREMENTAL, Duration.ofNanos(System.nanoTime() - evalStart));
        observation.pathSelected(Path.INCREMENTAL, Reason.INCREMENTAL_HIT);
        return buildPrepared(
                eventRef.potId(), eventRef.version(), Optional.of(balance), Optional.of(composed));
    }

    private PotBalanceAggregate loadAggregatePayload(
            UUID potId, AggregateRef ref, PreparationReads reads) {
        long start = System.nanoTime();
        final Optional<PotBalanceAggregate> loaded;
        try {
            loaded =
                    reads.effects(
                            c -> store.readAggregateByHash(potId, ref.version(), ref.aggregateHash(), c));
        } catch (RuntimeException | Error fault) {
            if (fault instanceof IllegalStateException) {
                observation.aggregateLookup(
                        LookupOutcome.CORRUPT, 0L, Duration.ofNanos(System.nanoTime() - start));
            }
            throw fault;
        }
        if (loaded.isEmpty()) {
            observation.aggregateLookup(
                    LookupOutcome.CORRUPT, 0L, Duration.ofNanos(System.nanoTime() - start));
            throw new IllegalStateException(
                    "Missing PotBalanceAggregate content for hash " + ref.aggregateHash());
        }
        PotBalanceAggregate previous = loaded.get();
        if (!potId.equals(previous.potId()) || previous.version() != ref.version()) {
            observation.aggregateLookup(
                    LookupOutcome.CORRUPT, 0L, Duration.ofNanos(System.nanoTime() - start));
            throw new IllegalStateException("Incompatible reference PotBalanceAggregate");
        }
        return previous;
    }

    private Optional<Prepared> prepareFull(UUID eventId, PreparationReads reads) {
        var state = reads.source(c -> source.load(eventId, c));
        if (state.header().value().deleted()) {
            return buildPrepared(
                    state.potId().value(), state.version(), Optional.empty(), Optional.empty());
        }
        long composeStart = System.nanoTime();
        var agg = aggregateCalculator.fromState(state).orElseThrow();
        observation.aggregateComputed(
                agg.bucketCount(),
                agg.bucketEntryCount(),
                Duration.ofNanos(System.nanoTime() - composeStart));
        boolean persistAggregate = isWithinAggregateLimits(agg);
        long evalStart = System.nanoTime();
        PotBalance balance =
                persistAggregate
                        ? aggregateCalculator.calculate(agg)
                        : calculateFull(state).orElseThrow();
        observation.balanceEvaluated(Path.FULL, Duration.ofNanos(System.nanoTime() - evalStart));
        return buildPrepared(
                state.potId().value(),
                state.version(),
                Optional.of(balance),
                persistAggregate ? Optional.of(agg) : Optional.empty());
    }

    private Optional<Prepared> buildReusePrepared(
            UUID potId, long version, ContentHash balanceHash, ContentHash aggregateHash) {
        return Optional.of(
                new Prepared() {
                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        long start = System.nanoTime();
                        store.linkExistingVersion(potId, version, balanceHash, aggregateHash);
                        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                        observation.persisted(PersistTarget.BALANCE, elapsed);
                        observation.contentWritten(PersistTarget.BALANCE, ContentOutcome.REUSED);
                        observation.contentWritten(PersistTarget.AGGREGATE, ContentOutcome.REUSED);
                        completion.complete();
                        store.closePublication();
                    }

                    @Override
                    public void requireCompletionReady() {
                        store.requireVersionHashesStored(potId, version, balanceHash, aggregateHash);
                    }
                });
    }

    private Optional<Prepared> buildPrepared(
            UUID potId,
            long version,
            Optional<PotBalance> balance,
            Optional<PotBalanceAggregate> aggregateToPersist) {
        return Optional.of(
                new Prepared() {
                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        if (balance.isPresent()) {
                            long balStart = System.nanoTime();
                            var balResult = store.insertOrVerifyWithResult(balance.get());
                            observation.persisted(
                                    PersistTarget.BALANCE,
                                    Duration.ofNanos(System.nanoTime() - balStart));
                            observation.contentEncoded(
                                    PersistTarget.BALANCE, balResult.encodeDuration());
                            observation.contentWritten(
                                    PersistTarget.BALANCE, toContentOutcome(balResult.outcome()));
                            if (aggregateToPersist.isPresent()) {
                                long aggStart = System.nanoTime();
                                var aggResult =
                                        store.insertOrVerifyAggregateWithResult(
                                                aggregateToPersist.get());
                                observation.persisted(
                                        PersistTarget.AGGREGATE,
                                        Duration.ofNanos(System.nanoTime() - aggStart));
                                observation.contentEncoded(
                                        PersistTarget.AGGREGATE, aggResult.encodeDuration());
                                observation.contentWritten(
                                        PersistTarget.AGGREGATE,
                                        toContentOutcome(aggResult.outcome()));
                            }
                        }
                        completion.complete();
                        store.closePublication();
                    }

                    @Override
                    public void requireCompletionReady() {
                        store.requireStored(potId, version, balance);
                        aggregateToPersist.ifPresent(store::requireAggregateStored);
                    }
                });
    }

    private static ContentOutcome toContentOutcome(ContentWriteOutcome outcome) {
        return outcome == ContentWriteOutcome.REUSED
                ? ContentOutcome.REUSED
                : ContentOutcome.INSERTED;
    }

    @Override
    public Recovery prepareRecovery(UUID id, PreparationReads reads) {
        var state = reads.source(c -> source.load(id, c));
        var expected = calculateFull(state);
        return c ->
                EffectEvidence.valueOf(
                        store.evidence(state.potId().value(), state.version(), expected, c).name());
    }
}
