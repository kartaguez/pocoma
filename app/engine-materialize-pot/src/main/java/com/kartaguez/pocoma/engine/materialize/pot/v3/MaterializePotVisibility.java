package com.kartaguez.pocoma.engine.materialize.pot.v3;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.domain.projection.v3.PotVisibility;
import com.kartaguez.pocoma.port.projection.v3.PotVisibilityStore;
import java.util.*;
public final class MaterializePotVisibility implements ConsumptionParticipant {
    private final PotVisibilitySource source; private final PotVisibilityStore store;
    public MaterializePotVisibility(PotVisibilitySource source,PotVisibilityStore store) { this.source=Objects.requireNonNull(source); this.store=Objects.requireNonNull(store); }
    public Optional<Prepared> prepare(UUID event,PreparationReads reads) {
        var value=reads.source(c -> source.load(event,c));
        return Optional.of(new Prepared() {
            private PotVisibilityStore.Receipt receipt;
            public void finalizeIn(SlotCompletion completion) { receipt=store.apply(value); completion.complete(); store.closePublication(); }
            public void requireCompletionReady() { store.requireStored(Objects.requireNonNull(receipt)); }
        });
    }
    public Recovery prepareRecovery(UUID event,PreparationReads reads) {
        var incoming=reads.source(c -> source.load(event,c));
        var observed=reads.effects(c -> store.read(incoming.potId(),c));
        var expected=observed.filter(v -> v.version()>incoming.version())
            .map(v -> reads.source(c -> source.at(v.potId(),v.version(),c))).orElse(incoming);
        return c -> {
            var actual=store.read(incoming.potId(),c);
            if(actual.isEmpty() || actual.get().version()<incoming.version()) return EffectEvidence.MISSING;
            if(actual.get().version()>expected.version()) return EffectEvidence.UNAVAILABLE;
            return actual.get().equals(expected) ? EffectEvidence.SATISFIED : EffectEvidence.DIVERGENT;
        };
    }
}
