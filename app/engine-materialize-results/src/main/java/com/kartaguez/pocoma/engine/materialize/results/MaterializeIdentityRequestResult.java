package com.kartaguez.pocoma.engine.materialize.results;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.port.projection.IdentityTerminalResultStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MaterializeIdentityRequestResult implements ConsumptionParticipant {
    private final IdentityTerminalSource source;
    private final IdentityTerminalResultStore store;

    public MaterializeIdentityRequestResult(IdentityTerminalSource source, IdentityTerminalResultStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return Optional.of(new Prepared() {
            private Boolean inserted;

            @Override
            public void finalizeIn(SlotCompletion completion) {
                inserted = store.insertOrVerify(expected);
                completion.complete();
                store.closePublication(expected.requestId(), inserted);
            }

            @Override
            public void requireCompletionReady() {
                store.requireCompletionReady(expected, Objects.requireNonNull(inserted, "Projection write not attempted"));
            }
        });
    }

    @Override
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return context -> EffectEvidence.valueOf(store.evidence(expected, context).name());
    }
}
