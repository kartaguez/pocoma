package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.port.projection.PotVisibilityStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MaterializePotVisibility implements ConsumptionParticipant {
    private final PotVisibilitySource source;
    private final PotVisibilityStore store;

    public MaterializePotVisibility(PotVisibilitySource source, PotVisibilityStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var value = reads.source(context -> source.load(event, context));
        return Optional.of(new Prepared() {
            private PotVisibilityStore.Receipt receipt;

            @Override
            public void finalizeIn(SlotCompletion completion) {
                receipt = store.apply(value);
                completion.complete();
                store.closePublication(receipt);
            }

            @Override
            public void requireCompletionReady() {
                store.requireCompletionReady(Objects.requireNonNull(receipt, "Visibility write not attempted"));
            }
        });
    }

    @Override
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var incoming = reads.source(context -> source.load(event, context));
        var observed = reads.effects(context -> store.read(incoming.potId(), context));
        var expected = observed.filter(value -> value.version() > incoming.version())
                .map(value -> reads.source(context -> source.at(value.potId(), value.version(), context)))
                .orElse(incoming);
        return context -> EffectEvidence.valueOf(store.evidence(incoming, expected, context).name());
    }
}
