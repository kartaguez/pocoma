package com.kartaguez.pocoma.engine.materialize.currentbinding.v3;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.port.projection.v3.CurrentBindingStore;
import java.util.*;
public final class MaterializeCurrentBinding implements ConsumptionParticipant {
    private final CurrentBindingSource source; private final CurrentBindingStore store;
    public MaterializeCurrentBinding(CurrentBindingSource source,CurrentBindingStore store) { this.source=Objects.requireNonNull(source); this.store=Objects.requireNonNull(store); }
    public Optional<Prepared> prepare(UUID event,PreparationReads reads) {
        return reads.source(c -> source.load(event,c)).map(value -> new Prepared() {
            public void finalizeIn(SlotCompletion completion) { store.apply(value); completion.complete(); store.closePublication(); }
            public void requireCompletionReady() { store.requireStored(value); }
        });
    }
    public Recovery prepareRecovery(UUID event,PreparationReads reads) {
        var incoming=reads.source(c -> source.load(event,c)); if(incoming.isEmpty()) return c -> EffectEvidence.DIVERGENT;
        var value=incoming.get(); var observed=reads.effects(c -> store.read(value.identity(),c));
        var expected=observed.filter(v -> v.version()>value.version()).map(v -> reads.source(c -> source.at(v.id(),v.version(),c))).orElse(value);
        return c -> {
            var actual=store.read(value.identity(),c);
            if(actual.isEmpty() || actual.get().version()<value.version()) return EffectEvidence.MISSING;
            if(actual.get().version()>expected.version()) return EffectEvidence.UNAVAILABLE;
            return actual.get().equals(expected) ? EffectEvidence.SATISFIED : EffectEvidence.DIVERGENT;
        };
    }
}
