package com.kartaguez.pocoma.engine.materialize.pot;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;

import com.kartaguez.pocoma.port.projection.InvitationStore;

import java.util.*;

/** Independent Invitation participant; no decryption capability. */
public final class MaterializeInvitation implements ConsumptionParticipant {
    private final InvitationSource source;
    private final InvitationStore store;

    public MaterializeInvitation(InvitationSource source, InvitationStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return Optional.of(
                new Prepared() {
                    private Boolean advanced;

                    public void finalizeIn(SlotCompletion completion) {
                        advanced = store.put(expected);
                        completion.complete();
                        store.closePublication(expected, advanced);
                    }

                    public void requireCompletionReady() {
                        store.requireCompletionReady(expected, Objects.requireNonNull(advanced, "Projection write not attempted"));
                    }
                });
    }

    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return context -> EffectEvidence.valueOf(store.evidence(expected, context).name());
    }
}
