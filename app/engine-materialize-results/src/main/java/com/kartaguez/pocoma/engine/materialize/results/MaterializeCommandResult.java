package com.kartaguez.pocoma.engine.materialize.results;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.port.projection.CommandTerminalResultStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MaterializeCommandResult implements ConsumptionParticipant {
    private final CommandTerminalSource source;
    private final CommandTerminalResultStore store;

    public MaterializeCommandResult(CommandTerminalSource source, CommandTerminalResultStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var expected = reads.source(context -> source.load(event, context));
        return Optional.of(
                new Prepared() {
                    private Boolean inserted;

                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        inserted = store.insertOrVerify(expected);
                        completion.complete();
                        store.closePublication(expected.requestId(), inserted);
                    }

                    @Override
                    public void requireCompletionReady() {
                        store.requireCompletionReady(
                                expected, Objects.requireNonNull(inserted, "Result write not attempted"));
                    }
                });
    }

    @Override
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(context -> source.load(event, context));
        return context -> EffectEvidence.valueOf(store.evidence(expected, context).name());
    }
}
