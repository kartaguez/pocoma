package com.kartaguez.pocoma.engine.materialize.identity;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.port.projection.CurrentBindingStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MaterializeCurrentBinding implements ConsumptionParticipant {
    private final CurrentBindingSource source;
    private final CurrentBindingStore store;

    public MaterializeCurrentBinding(CurrentBindingSource source, CurrentBindingStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        return reads.source(context -> source.load(event, context)).map(value -> new Prepared() {
            private Boolean advanced;

            @Override
            public void finalizeIn(SlotCompletion completion) {
                advanced = store.apply(value);
                completion.complete();
                store.closePublication(value, advanced);
            }

            @Override
            public void requireCompletionReady() {
                store.requireCompletionReady(
                        value, Objects.requireNonNull(advanced, "Binding write not attempted"));
            }
        });
    }

    @Override
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var loaded = reads.source(context -> source.load(event, context));
        if (loaded.isEmpty()) {
            return context -> EffectEvidence.DIVERGENT;
        }
        var incoming = loaded.get();
        var observed = reads.effects(context -> store.read(incoming.identity(), context));
        var expected = observed.filter(value -> value.version() > incoming.version())
                .map(value -> reads.source(context -> source.at(value.id(), value.version(), context)))
                .orElse(incoming);
        return context -> EffectEvidence.valueOf(store.evidence(incoming, expected, context).name());
    }
}
