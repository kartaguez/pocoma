package com.kartaguez.pocoma.engine.materialize.registrationresult.v3;
import com.kartaguez.pocoma.port.projection.v3.IdentityTerminalResultStore;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import java.util.*;
public final class MaterializeIdentityRequestResult implements ConsumptionParticipant {
    private final IdentityTerminalSource source; private final IdentityTerminalResultStore store;
    public MaterializeIdentityRequestResult(IdentityTerminalSource source,IdentityTerminalResultStore store) { this.source=Objects.requireNonNull(source); this.store=Objects.requireNonNull(store); }
    public Optional<Prepared> prepare(UUID event,PreparationReads reads) {
        var expected=reads.source(c -> source.load(event,c));
        return Optional.of(new Prepared() {
            public void finalizeIn(SlotCompletion completion) { store.insertOrVerify(expected); completion.complete(); store.closePublication(); }
            public void requireCompletionReady() { store.requireStored(expected); }
        });
    }
    public Recovery prepareRecovery(UUID event,PreparationReads reads) {
        var expected=reads.source(c -> source.load(event,c));
        return c -> { var actual=store.find(expected.requestId()); return actual.isEmpty()?EffectEvidence.MISSING:actual.get().equals(expected)?EffectEvidence.SATISFIED:EffectEvidence.DIVERGENT; };
    }
}
