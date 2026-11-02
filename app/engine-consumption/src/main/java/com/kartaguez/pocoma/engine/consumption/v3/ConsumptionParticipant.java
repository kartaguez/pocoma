package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.UUID;

public interface ConsumptionParticipant {
    /** Empty when already terminal: Engine must observe rather than finalize again. */
    java.util.Optional<Prepared> prepare(UUID consumableId, PreparationReads reads);

    Recovery prepareRecovery(UUID consumableId, PreparationReads reads);

    enum EffectEvidence {
        SATISFIED,
        MISSING,
        DIVERGENT,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface Recovery {
        EffectEvidence observe(RecoveryReadContext context);
    }

    interface Prepared {
        void finalizeIn(SlotCompletion completion);

        /** Verify the actual durable business prefix in the final transaction, before DELETE. */
        void requireCompletionReady();
    }
}
