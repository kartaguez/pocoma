package com.kartaguez.pocoma.contract.consumption.spi;

import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Strategy encapsulating authority-specific source, checkpoint, and slot recovery rules so that
 * consumption orchestration remains agnostic to {@link ConsumptionDefinition.Authority}.
 */
public interface RecoverySourcePolicy {

    /** Validates transaction ownership for this policy's authority. */
    void validate(
            ConsumptionDefinition definition,
            AttemptTransactions effectsTransactions,
            AttemptTransactions sourceTransactions);

    /** Reads and validates the upstream source reference if this policy requires one. */
    SourceCheck checkSource(
            ConsumptionDefinition definition, UUID consumableId, PreparationReads reads);

    /** Verifies whether the admission checkpoint covers the checked source reference. */
    boolean checkpointCovers(
            ConsumptionDefinition definition,
            UUID consumableId,
            NonBurnableReferences.Reference source,
            RecoveryReadContext context);

    /** Outcome when business effect is SATISFIED while the consumption slot is still present. */
    RecoveryObservation satisfiedWithPresentSlot();

    record SourceCheck(boolean consistent, NonBurnableReferences.Reference reference) {
        public static SourceCheck none() {
            return new SourceCheck(true, null);
        }

        public static SourceCheck inconsistent() {
            return new SourceCheck(false, null);
        }

        public static SourceCheck valid(NonBurnableReferences.Reference reference) {
            return new SourceCheck(true, Objects.requireNonNull(reference));
        }
    }

    static RecoverySourcePolicy forAuthority(
            ConsumptionDefinition definition,
            Optional<NonBurnableReferences> recoverySource,
            Predicate<NonBurnableReferences.Reference> relevant,
            DiscoveryAdmissionStore admissions) {
        return switch (definition.authority()) {
            case WRITE -> new WritePolicy(recoverySource);
            case READ -> new ReadPolicy(definition, recoverySource, relevant, admissions);
        };
    }

    final class WritePolicy implements RecoverySourcePolicy {
        private final Optional<NonBurnableReferences> recoverySource;

        public WritePolicy(Optional<NonBurnableReferences> recoverySource) {
            this.recoverySource = Objects.requireNonNull(recoverySource);
        }

        @Override
        public void validate(
                ConsumptionDefinition definition,
                AttemptTransactions effectsTransactions,
                AttemptTransactions sourceTransactions) {
            if (sourceTransactions != effectsTransactions || recoverySource.isPresent()) {
                throw new IllegalArgumentException(
                        "WRITE requires one source/effects owner and no event lookup");
            }
        }

        @Override
        public SourceCheck checkSource(
                ConsumptionDefinition definition, UUID consumableId, PreparationReads reads) {
            return SourceCheck.none();
        }

        @Override
        public boolean checkpointCovers(
                ConsumptionDefinition definition,
                UUID consumableId,
                NonBurnableReferences.Reference source,
                RecoveryReadContext context) {
            return true;
        }

        @Override
        public RecoveryObservation satisfiedWithPresentSlot() {
            return RecoveryObservation.INCONSISTENT;
        }
    }

    final class ReadPolicy implements RecoverySourcePolicy {
        private final NonBurnableReferences recoverySource;
        private final Predicate<NonBurnableReferences.Reference> relevant;
        private final DiscoveryAdmissionStore admissions;

        public ReadPolicy(
                ConsumptionDefinition definition,
                Optional<NonBurnableReferences> recoverySource,
                Predicate<NonBurnableReferences.Reference> relevant,
                DiscoveryAdmissionStore admissions) {
            if (recoverySource.isEmpty() || recoverySource.get().family() != definition.family()) {
                throw new IllegalArgumentException("READ requires its exact source family");
            }
            this.recoverySource = recoverySource.get();
            this.relevant = Objects.requireNonNull(relevant);
            this.admissions = Objects.requireNonNull(admissions, "READ requires effects admissions");
        }

        @Override
        public void validate(
                ConsumptionDefinition definition,
                AttemptTransactions effectsTransactions,
                AttemptTransactions sourceTransactions) {
            // Validated at construction time.
        }

        @Override
        public SourceCheck checkSource(
                ConsumptionDefinition definition, UUID consumableId, PreparationReads reads) {
            var found = reads.source(ctx -> recoverySource.find(consumableId, ctx));
            if (found.isEmpty()
                    || !found.get().eventId().equals(consumableId)
                    || !relevant.test(found.get())) {
                return SourceCheck.inconsistent();
            }
            return SourceCheck.valid(found.get());
        }

        @Override
        public boolean checkpointCovers(
                ConsumptionDefinition definition,
                UUID consumableId,
                NonBurnableReferences.Reference source,
                RecoveryReadContext context) {
            if (source == null) {
                return true;
            }
            long checkpoint =
                    admissions.checkpoint(
                            definition.id(),
                            RoutingV1.partition(
                                    RoutingV1.hash(definition.family(), consumableId),
                                    definition.partitionCount()),
                            context);
            return checkpoint >= source.position();
        }

        @Override
        public RecoveryObservation satisfiedWithPresentSlot() {
            return RecoveryObservation.NOT_COMPLETED;
        }
    }
}
